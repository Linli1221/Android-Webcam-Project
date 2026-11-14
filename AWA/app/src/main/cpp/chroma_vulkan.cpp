// Vulkan compute implementation of the green screen keyer.
//
// Two dispatches per frame, one per NV21 plane, both writing whole 32-bit words because byte
// granular stores into a shared storage buffer would race between invocations:
//
//   pass 1 (chroma_key_y.spv)  - four luma bytes per invocation, alpha from the 2x2 cell's chroma
//   pass 2 (chroma_key_uv.spv) - two cells (V,U,V,U) per invocation, plus the key colour sampling
//
// The matte itself is the same maths as the CPU keyer, so switching backends does not change the
// look. Everything the GPU needs lives in host visible, persistently mapped memory: the analysis
// frame is copied straight from the Java byte array into the mapped input buffer, the kernel runs,
// and the result is copied straight back out. No staging buffers, no per frame allocations.
//
// This class is deliberately failure tolerant: any error at creation time or mid stream returns
// false and the caller falls back to the CPU keyer.

#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <vulkan/vulkan.h>

#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#define LOG_TAG "AWA-Vulkan"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define CHECK_VK(expr)                                            \
    do {                                                          \
        VkResult _vkResult = (expr);                              \
        if (_vkResult != VK_SUCCESS) {                            \
            LOGE("%s failed (%d)", #expr, (int)_vkResult);         \
            return false;                                         \
        }                                                         \
    } while (0)

namespace {

/** Must match the std140 block in both shaders. */
struct Params {
    uint32_t dims[4];   // width, height, sampleKey, unused
    float key[4];       // Cb, Cr, similarity threshold, soft edge width
    float repair[4];    // spill, squared despill radius, blueKey, unused
};
static_assert(sizeof(Params) == 48, "std140 uniform block size");

/** Chroma unit used by the sliders, same constant as the CPU keyer. */
constexpr float kMaxChroma = 128.0f;
constexpr uint32_t kWorkgroup = 8;
/** A frame that takes longer than this is a hung GPU, not a slow one. */
constexpr uint64_t kFenceTimeoutNs = 2000000000ull;

struct Buffer {
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkDeviceSize size = 0;
    void* mapped = nullptr;
    bool coherent = true;
};

class Keyer {
public:
    ~Keyer() { shutdown(); }

    bool init(AAssetManager* assets, const char* yAsset, const char* uvAsset);
    void shutdown();

    /** Sizes the buffers for this frame; false when the size cannot be handled on the GPU. */
    bool beginFrame(uint32_t width, uint32_t height);
    uint8_t* srcHost() const { return static_cast<uint8_t*>(src_.mapped); }
    uint8_t* dstHost() const { return static_cast<uint8_t*>(dst_.mapped); }

    void setKeyColor(float cb, float cr) { keyU_ = cb; keyV_ = cr; }
    void setLook(float similarity, float smoothness, float spill, bool blueKey);

    bool dispatchFrame(bool sampleKey);
    bool uploadBackground(const uint8_t* y, const uint8_t* v, const uint8_t* u);
    void readStats(uint32_t out[4]) const;
    const char* deviceName() const { return deviceName_.c_str(); }

private:
    bool createInstance();
    bool createDevice();
    bool createDescriptorSet();
    bool createPipelines(const std::vector<char>& ySpv, const std::vector<char>& uvSpv);
    bool recordCommandBuffer();
    void releaseFrameObjects();

    bool createBuffer(VkDeviceSize size, VkBufferUsageFlags usage, VkMemoryPropertyFlags props, Buffer& out);
    void destroyBuffer(Buffer& b);
    uint32_t findMemoryType(uint32_t typeBits, VkMemoryPropertyFlags props) const;
    static bool loadAsset(AAssetManager* assets, const char* name, std::vector<char>& out);

    void flushBuffer(const Buffer& b) const;
    void invalidateBuffer(const Buffer& b) const;

    VkInstance instance_ = VK_NULL_HANDLE;
    VkPhysicalDevice gpu_ = VK_NULL_HANDLE;
    VkDevice device_ = VK_NULL_HANDLE;
    VkQueue queue_ = VK_NULL_HANDLE;
    uint32_t queueFamily_ = 0;
    VkPhysicalDeviceMemoryProperties memoryProperties_{};

    VkCommandPool commandPool_ = VK_NULL_HANDLE;
    VkCommandBuffer commandBuffer_ = VK_NULL_HANDLE;
    VkFence fence_ = VK_NULL_HANDLE;

    VkDescriptorSetLayout setLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout_ = VK_NULL_HANDLE;
    VkDescriptorPool descriptorPool_ = VK_NULL_HANDLE;
    VkDescriptorSet descriptorSet_ = VK_NULL_HANDLE;

    VkPipeline pipelineY_ = VK_NULL_HANDLE;
    VkPipeline pipelineUV_ = VK_NULL_HANDLE;

    Buffer src_{}, dst_{}, bg_{}, params_{}, stats_{};

    uint32_t width_ = 0;
    uint32_t height_ = 0;
    uint32_t groupsX_ = 0;
    uint32_t groupsY_ = 0;
    bool recorded_ = false;

    float keyU_ = 43.0f;
    float keyV_ = 21.0f;
    float threshold_ = 0.28f * kMaxChroma;
    float fade_ = 0.12f * kMaxChroma;
    float spill_ = 0.45f;
    float guard2_ = 1.0e12f;
    bool blueKey_ = false;

    std::string deviceName_;
};

bool Keyer::loadAsset(AAssetManager* assets, const char* name, std::vector<char>& out) {
    if (assets == nullptr) return false;
    AAsset* asset = AAssetManager_open(assets, name, AASSET_MODE_BUFFER);
    if (asset == nullptr) {
        LOGE("Asset %s is missing - rebuild the shaders, see AWA/tools/build_shaders.mjs", name);
        return false;
    }
    const off_t length = AAsset_getLength(asset);
    if (length <= 0 || (length % 4) != 0) {
        LOGE("Asset %s has an invalid size (%ld)", name, static_cast<long>(length));
        AAsset_close(asset);
        return false;
    }
    out.resize(static_cast<size_t>(length));
    const int read = AAsset_read(asset, out.data(), static_cast<size_t>(length));
    AAsset_close(asset);
    if (read != length) {
        LOGE("Short read on %s", name);
        return false;
    }
    return true;
}

bool Keyer::init(AAssetManager* assets, const char* yAsset, const char* uvAsset) {
    std::vector<char> ySpv;
    std::vector<char> uvSpv;
    if (!loadAsset(assets, yAsset, ySpv) || !loadAsset(assets, uvAsset, uvSpv)) return false;
    if (!createInstance() || !createDevice()) return false;
    if (!createDescriptorSet()) return false;
    if (!createPipelines(ySpv, uvSpv)) return false;
    LOGI("Vulkan keyer ready on %s", deviceName_.c_str());
    return true;
}

bool Keyer::createInstance() {
    VkApplicationInfo appInfo{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    appInfo.pApplicationName = "AWA";
    appInfo.applicationVersion = 1;
    appInfo.pEngineName = "AWA-Chroma";
    appInfo.engineVersion = 1;
    appInfo.apiVersion = VK_API_VERSION_1_0;

    // Compute only: no surface, so no instance extensions are needed.
    VkInstanceCreateInfo createInfo{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    createInfo.pApplicationInfo = &appInfo;

    const VkResult result = vkCreateInstance(&createInfo, nullptr, &instance_);
    if (result != VK_SUCCESS) {
        // VK_ERROR_INCOMPATIBLE_DRIVER is the normal answer on devices without Vulkan.
        LOGE("vkCreateInstance failed (%d)", static_cast<int>(result));
        return false;
    }
    return true;
}

bool Keyer::createDevice() {
    uint32_t count = 0;
    CHECK_VK(vkEnumeratePhysicalDevices(instance_, &count, nullptr));
    if (count == 0) {
        LOGE("No Vulkan device");
        return false;
    }
    std::vector<VkPhysicalDevice> devices(count);
    CHECK_VK(vkEnumeratePhysicalDevices(instance_, &count, devices.data()));

    for (VkPhysicalDevice candidate : devices) {
        VkPhysicalDeviceProperties props{};
        vkGetPhysicalDeviceProperties(candidate, &props);
        if (props.apiVersion < VK_API_VERSION_1_0) continue;

        uint32_t familyCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, &familyCount, nullptr);
        std::vector<VkQueueFamilyProperties> families(familyCount);
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, &familyCount, families.data());

        for (uint32_t i = 0; i < familyCount; ++i) {
            if ((families[i].queueFlags & VK_QUEUE_COMPUTE_BIT) == 0) continue;
            gpu_ = candidate;
            queueFamily_ = i;
            deviceName_ = props.deviceName;
            break;
        }
        if (gpu_ != VK_NULL_HANDLE) break;
    }

    if (gpu_ == VK_NULL_HANDLE) {
        LOGE("No Vulkan device with a compute queue");
        return false;
    }

    const float priority = 1.0f;
    VkDeviceQueueCreateInfo queueInfo{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    queueInfo.queueFamilyIndex = queueFamily_;
    queueInfo.queueCount = 1;
    queueInfo.pQueuePriorities = &priority;

    VkDeviceCreateInfo deviceInfo{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    deviceInfo.queueCreateInfoCount = 1;
    deviceInfo.pQueueCreateInfos = &queueInfo;

    CHECK_VK(vkCreateDevice(gpu_, &deviceInfo, nullptr, &device_));
    vkGetDeviceQueue(device_, queueFamily_, 0, &queue_);
    vkGetPhysicalDeviceMemoryProperties(gpu_, &memoryProperties_);

    VkCommandPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    // The command buffer is re-recorded whenever the frame size changes, which needs the pool's
    // reset flag (resetting a single command buffer is otherwise illegal).
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    poolInfo.queueFamilyIndex = queueFamily_;
    CHECK_VK(vkCreateCommandPool(device_, &poolInfo, nullptr, &commandPool_));

    VkCommandBufferAllocateInfo allocInfo{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    allocInfo.commandPool = commandPool_;
    allocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocInfo.commandBufferCount = 1;
    CHECK_VK(vkAllocateCommandBuffers(device_, &allocInfo, &commandBuffer_));

    VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    CHECK_VK(vkCreateFence(device_, &fenceInfo, nullptr, &fence_));

    VkPhysicalDeviceProperties props{};
    vkGetPhysicalDeviceProperties(gpu_, &props);
    const uint32_t maxGroups = props.limits.maxComputeWorkGroupCount[0];
    if (maxGroups < 1024) {
        LOGE("Dispatch limit too small (%u)", maxGroups);
        return false;
    }
    LOGI("Device: %s (API %u.%u)", deviceName_.c_str(),
         VK_VERSION_MAJOR(props.apiVersion), VK_VERSION_MINOR(props.apiVersion));
    return true;
}

uint32_t Keyer::findMemoryType(uint32_t typeBits, VkMemoryPropertyFlags props) const {
    for (uint32_t i = 0; i < memoryProperties_.memoryTypeCount; ++i) {
        if ((typeBits & (1u << i)) == 0) continue;
        if ((memoryProperties_.memoryTypes[i].propertyFlags & props) == props) return i;
    }
    return UINT32_MAX;
}

bool Keyer::createBuffer(VkDeviceSize size, VkBufferUsageFlags usage, VkMemoryPropertyFlags props,
                         Buffer& out) {
    VkBufferCreateInfo bufferInfo{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bufferInfo.size = size;
    bufferInfo.usage = usage;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    CHECK_VK(vkCreateBuffer(device_, &bufferInfo, nullptr, &out.buffer));

    VkMemoryRequirements requirements{};
    vkGetBufferMemoryRequirements(device_, out.buffer, &requirements);

    uint32_t typeIndex = findMemoryType(requirements.memoryTypeBits, props);
    bool coherent = (props & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
    if (typeIndex == UINT32_MAX && (props & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0) {
        // Some drivers expose host visible memory that is only flushable, not coherent.
        typeIndex = findMemoryType(requirements.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT);
        coherent = false;
    }
    if (typeIndex == UINT32_MAX) {
        LOGE("No suitable memory type for a %llu byte buffer",
             static_cast<unsigned long long>(size));
        return false;
    }

    VkMemoryAllocateInfo allocInfo{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    allocInfo.allocationSize = requirements.size;
    allocInfo.memoryTypeIndex = typeIndex;
    CHECK_VK(vkAllocateMemory(device_, &allocInfo, nullptr, &out.memory));
    CHECK_VK(vkBindBufferMemory(device_, out.buffer, out.memory, 0));

    out.size = size;
    out.coherent = coherent;
    if ((props & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) != 0) {
        CHECK_VK(vkMapMemory(device_, out.memory, 0, VK_WHOLE_SIZE, 0, &out.mapped));
    }
    return true;
}

void Keyer::destroyBuffer(Buffer& b) {
    if (device_ == VK_NULL_HANDLE) {
        // Shutting down after a failed init: nothing was ever created.
        b = Buffer{};
        return;
    }
    if (b.mapped != nullptr) {
        vkUnmapMemory(device_, b.memory);
        b.mapped = nullptr;
    }
    if (b.buffer != VK_NULL_HANDLE) {
        vkDestroyBuffer(device_, b.buffer, nullptr);
        b.buffer = VK_NULL_HANDLE;
    }
    if (b.memory != VK_NULL_HANDLE) {
        vkFreeMemory(device_, b.memory, nullptr);
        b.memory = VK_NULL_HANDLE;
    }
    b.size = 0;
}

void Keyer::flushBuffer(const Buffer& b) const {
    if (b.mapped == nullptr || b.coherent) return;
    VkMappedMemoryRange range{VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE};
    range.memory = b.memory;
    range.offset = 0;
    range.size = VK_WHOLE_SIZE;
    vkFlushMappedMemoryRanges(device_, 1, &range);
}

void Keyer::invalidateBuffer(const Buffer& b) const {
    if (b.mapped == nullptr || b.coherent) return;
    VkMappedMemoryRange range{VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE};
    range.memory = b.memory;
    range.offset = 0;
    range.size = VK_WHOLE_SIZE;
    vkInvalidateMappedMemoryRanges(device_, 1, &range);
}

bool Keyer::createDescriptorSet() {
    VkDescriptorSetLayoutBinding bindings[5]{};
    for (uint32_t i = 0; i < 5; ++i) {
        bindings[i].binding = i;
        bindings[i].descriptorType =
                (i == 0) ? VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER : VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        bindings[i].descriptorCount = 1;
        bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }

    VkDescriptorSetLayoutCreateInfo layoutInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    layoutInfo.bindingCount = 5;
    layoutInfo.pBindings = bindings;
    CHECK_VK(vkCreateDescriptorSetLayout(device_, &layoutInfo, nullptr, &setLayout_));

    VkPipelineLayoutCreateInfo pipelineInfo{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    pipelineInfo.setLayoutCount = 1;
    pipelineInfo.pSetLayouts = &setLayout_;
    CHECK_VK(vkCreatePipelineLayout(device_, &pipelineInfo, nullptr, &pipelineLayout_));

    VkDescriptorPoolSize sizes[2]{};
    sizes[0].type = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    sizes[0].descriptorCount = 1;
    sizes[1].type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    sizes[1].descriptorCount = 4;

    VkDescriptorPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    poolInfo.maxSets = 1;
    poolInfo.poolSizeCount = 2;
    poolInfo.pPoolSizes = sizes;
    CHECK_VK(vkCreateDescriptorPool(device_, &poolInfo, nullptr, &descriptorPool_));

    VkDescriptorSetAllocateInfo allocInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    allocInfo.descriptorPool = descriptorPool_;
    allocInfo.descriptorSetCount = 1;
    allocInfo.pSetLayouts = &setLayout_;
    CHECK_VK(vkAllocateDescriptorSets(device_, &allocInfo, &descriptorSet_));
    return true;
}

bool Keyer::createPipelines(const std::vector<char>& ySpv, const std::vector<char>& uvSpv) {
    VkShaderModuleCreateInfo moduleInfo{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    moduleInfo.codeSize = ySpv.size();
    moduleInfo.pCode = reinterpret_cast<const uint32_t*>(ySpv.data());
    VkShaderModule yModule = VK_NULL_HANDLE;
    CHECK_VK(vkCreateShaderModule(device_, &moduleInfo, nullptr, &yModule));

    moduleInfo.codeSize = uvSpv.size();
    moduleInfo.pCode = reinterpret_cast<const uint32_t*>(uvSpv.data());
    VkShaderModule uvModule = VK_NULL_HANDLE;
    const VkResult uvResult = vkCreateShaderModule(device_, &moduleInfo, nullptr, &uvModule);

    bool ok = uvResult == VK_SUCCESS;
    if (ok) {
        VkComputePipelineCreateInfo info{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
        info.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        info.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        info.stage.module = yModule;
        info.stage.pName = "main";
        info.layout = pipelineLayout_;
        ok = vkCreateComputePipelines(device_, VK_NULL_HANDLE, 1, &info, nullptr, &pipelineY_) ==
             VK_SUCCESS;

        if (ok) {
            info.stage.module = uvModule;
            ok = vkCreateComputePipelines(device_, VK_NULL_HANDLE, 1, &info, nullptr,
                                          &pipelineUV_) == VK_SUCCESS;
        }
    } else {
        LOGE("vkCreateShaderModule for the chroma pass failed (%d)", static_cast<int>(uvResult));
    }

    vkDestroyShaderModule(device_, yModule, nullptr);
    if (uvModule != VK_NULL_HANDLE) vkDestroyShaderModule(device_, uvModule, nullptr);

    if (!ok) {
        LOGE("Compute pipeline creation failed");
        return false;
    }
    return true;
}

bool Keyer::beginFrame(uint32_t width, uint32_t height) {
    // Word aligned work splitting (see the shader headers) needs these.
    if (width < 16 || height < 16 || (width % 4) != 0 || (height % 2) != 0) return false;

    const VkDeviceSize bytes = static_cast<VkDeviceSize>(width) * height * 3 / 2;
    if (width == width_ && height == height_ && src_.buffer != VK_NULL_HANDLE &&
        bg_.buffer != VK_NULL_HANDLE) {
        return true;
    }

    releaseFrameObjects();
    width_ = width;
    height_ = height;

    const VkMemoryPropertyFlags hostFlags =
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
    if (!createBuffer(bytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, hostFlags, src_)) return false;
    if (!createBuffer(bytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, hostFlags, dst_)) return false;
    if (!createBuffer(bytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, hostFlags, bg_)) return false;
    if (!createBuffer(sizeof(Params), VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, hostFlags, params_))
        return false;
    if (!createBuffer(16, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, hostFlags, stats_)) return false;

    std::memset(bg_.mapped, 0, static_cast<size_t>(bytes));
    std::memset(stats_.mapped, 0, 16);

    VkDescriptorBufferInfo infos[5]{};
    VkWriteDescriptorSet writes[5]{};
    for (uint32_t i = 0; i < 5; ++i) {
        infos[i].buffer = (i == 0) ? params_.buffer
                                   : (i == 1) ? src_.buffer
                                              : (i == 2) ? dst_.buffer
                                                         : (i == 3) ? bg_.buffer : stats_.buffer;
        infos[i].offset = 0;
        infos[i].range = VK_WHOLE_SIZE;

        writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[i].dstSet = descriptorSet_;
        writes[i].dstBinding = i;
        writes[i].descriptorCount = 1;
        writes[i].descriptorType =
                (i == 0) ? VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER : VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[i].pBufferInfo = &infos[i];
    }
    vkUpdateDescriptorSets(device_, 5, writes, 0, nullptr);

    groupsX_ = (width / 4 + kWorkgroup - 1) / kWorkgroup;
    groupsY_ = (height + kWorkgroup - 1) / kWorkgroup;
    return recordCommandBuffer();
}

bool Keyer::recordCommandBuffer() {
    CHECK_VK(vkResetCommandBuffer(commandBuffer_, 0));

    VkCommandBufferBeginInfo beginInfo{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    // The same buffer is submitted once per frame, so it must be recorded as reusable.
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_SIMULTANEOUS_USE_BIT;
    CHECK_VK(vkBeginCommandBuffer(commandBuffer_, &beginInfo));

    vkCmdBindPipeline(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineY_);
    // Bind after the pipeline so the set is always validated against the layout in use.
    vkCmdBindDescriptorSets(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout_, 0, 1,
                            &descriptorSet_, 0, nullptr);
    vkCmdDispatch(commandBuffer_, groupsX_, groupsY_, 1);

    // Both passes write to the same buffer (disjoint ranges), and the chroma pass reads the luma
    // written above for its despill, so an explicit dependency is required.
    VkMemoryBarrier barrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    barrier.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT;
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &barrier, 0, nullptr, 0,
                         nullptr);

    vkCmdBindPipeline(commandBuffer_, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineUV_);
    vkCmdDispatch(commandBuffer_, groupsX_, (height_ / 2 + kWorkgroup - 1) / kWorkgroup, 1);

    // Make the result visible to the host reads that follow the fence wait.
    VkMemoryBarrier toHost{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    toHost.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    toHost.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    vkCmdPipelineBarrier(commandBuffer_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &toHost, 0, nullptr, 0, nullptr);

    CHECK_VK(vkEndCommandBuffer(commandBuffer_));
    recorded_ = true;
    return true;
}

void Keyer::setLook(float similarity, float smoothness, float spill, bool blueKey) {
    threshold_ = similarity * kMaxChroma;
    fade_ = smoothness * kMaxChroma;
    if (fade_ < 1.0f) fade_ = 1.0f;
    spill_ = spill;
    blueKey_ = blueKey;
    const float edge = threshold_ + fade_;
    guard2_ = (edge * 2.0f) * (edge * 2.0f);
}

bool Keyer::dispatchFrame(bool sampleKey) {
    if (!recorded_ || src_.mapped == nullptr) return false;

    Params params{};
    params.dims[0] = width_;
    params.dims[1] = height_;
    params.dims[2] = sampleKey ? 1u : 0u;
    params.dims[3] = 0;
    params.key[0] = keyU_;
    params.key[1] = keyV_;
    params.key[2] = threshold_;
    params.key[3] = fade_;
    params.repair[0] = spill_;
    params.repair[1] = guard2_;
    params.repair[2] = blueKey_ ? 1.0f : 0.0f;
    params.repair[3] = 0.0f;

    std::memcpy(params_.mapped, &params, sizeof(Params));
    flushBuffer(params_);
    flushBuffer(src_);
    if (sampleKey) {
        std::memset(stats_.mapped, 0, 16);
        flushBuffer(stats_);
    }

    // Bounded waits: a hung driver must degrade to the CPU keyer instead of freezing the analyzer
    // thread (and with it the video stream) forever.
    CHECK_VK(vkWaitForFences(device_, 1, &fence_, VK_TRUE, kFenceTimeoutNs));
    CHECK_VK(vkResetFences(device_, 1, &fence_));

    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &commandBuffer_;
    CHECK_VK(vkQueueSubmit(queue_, 1, &submit, fence_));
    CHECK_VK(vkWaitForFences(device_, 1, &fence_, VK_TRUE, kFenceTimeoutNs));

    invalidateBuffer(dst_);
    invalidateBuffer(stats_);
    return true;
}

bool Keyer::uploadBackground(const uint8_t* y, const uint8_t* v, const uint8_t* u) {
    if (bg_.mapped == nullptr) return false;
    const size_t lumaBytes = static_cast<size_t>(width_) * height_;
    const size_t chromaBytes = lumaBytes / 4;

    auto* dst = static_cast<uint8_t*>(bg_.mapped);
    std::memcpy(dst, y, lumaBytes);
    std::memcpy(dst + lumaBytes, v, chromaBytes);
    std::memcpy(dst + lumaBytes + chromaBytes, u, chromaBytes);
    flushBuffer(bg_);
    return true;
}

void Keyer::readStats(uint32_t out[4]) const {
    if (stats_.mapped == nullptr) {
        out[0] = out[1] = out[2] = out[3] = 0;
        return;
    }
    std::memcpy(out, stats_.mapped, 16);
}

void Keyer::releaseFrameObjects() {
    destroyBuffer(src_);
    destroyBuffer(dst_);
    destroyBuffer(bg_);
    destroyBuffer(params_);
    destroyBuffer(stats_);
    width_ = 0;
    height_ = 0;
    recorded_ = false;
}

void Keyer::shutdown() {
    if (device_ != VK_NULL_HANDLE) {
        vkDeviceWaitIdle(device_);
        releaseFrameObjects();
        if (pipelineY_ != VK_NULL_HANDLE) vkDestroyPipeline(device_, pipelineY_, nullptr);
        if (pipelineUV_ != VK_NULL_HANDLE) vkDestroyPipeline(device_, pipelineUV_, nullptr);
        if (descriptorPool_ != VK_NULL_HANDLE)
            vkDestroyDescriptorPool(device_, descriptorPool_, nullptr);
        if (pipelineLayout_ != VK_NULL_HANDLE)
            vkDestroyPipelineLayout(device_, pipelineLayout_, nullptr);
        if (setLayout_ != VK_NULL_HANDLE)
            vkDestroyDescriptorSetLayout(device_, setLayout_, nullptr);
        if (commandPool_ != VK_NULL_HANDLE) vkDestroyCommandPool(device_, commandPool_, nullptr);
        if (fence_ != VK_NULL_HANDLE) vkDestroyFence(device_, fence_, nullptr);
        vkDestroyDevice(device_, nullptr);
        device_ = VK_NULL_HANDLE;
    }
    if (instance_ != VK_NULL_HANDLE) {
        vkDestroyInstance(instance_, nullptr);
        instance_ = VK_NULL_HANDLE;
    }
    // One handle per statement: VK_NULL_HANDLE is nullptr in C++, so a chained assignment like
    // `a = b = VK_NULL_HANDLE` has the type of b on the right and does not compile when the two
    // handles are different types.
    pipelineY_ = VK_NULL_HANDLE;
    pipelineUV_ = VK_NULL_HANDLE;
    descriptorPool_ = VK_NULL_HANDLE;
    pipelineLayout_ = VK_NULL_HANDLE;
    setLayout_ = VK_NULL_HANDLE;
    commandPool_ = VK_NULL_HANDLE;
    fence_ = VK_NULL_HANDLE;
    commandBuffer_ = VK_NULL_HANDLE;
    descriptorSet_ = VK_NULL_HANDLE;
}

Keyer* fromHandle(jlong handle) { return reinterpret_cast<Keyer*>(handle); }

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_soubhagyajit_awa_processing_vulkan_VulkanChromaKey_nativeCreate(JNIEnv* env, jobject,
                                                                        jobject assetManager) {
    AAssetManager* manager = AAssetManager_fromJava(env, assetManager);
    if (manager == nullptr) {
        LOGE("No asset manager");
        return 0;
    }
    auto* keyer = new Keyer();
    if (!keyer->init(manager, "shaders/chroma_key_y.spv", "shaders/chroma_key_uv.spv")) {
        delete keyer;
        return 0;
    }
    return reinterpret_cast<jlong>(keyer);
}

JNIEXPORT void JNICALL
Java_com_soubhagyajit_awa_processing_vulkan_VulkanChromaKey_nativeDestroy(JNIEnv*, jobject,
                                                                         jlong handle) {
    delete fromHandle(handle);
}

JNIEXPORT jstring JNICALL
Java_com_soubhagyajit_awa_processing_vulkan_VulkanChromaKey_nativeDeviceName(JNIEnv* env, jobject,
                                                                            jlong handle) {
    Keyer* keyer = fromHandle(handle);
    if (keyer == nullptr) return env->NewStringUTF("");
    return env->NewStringUTF(keyer->deviceName());
}

/** similarity / smoothness are the 0..1 slider values; the shader works in chroma units. */
JNIEXPORT void JNICALL
Java_com_soubhagyajit_awa_processing_vulkan_VulkanChromaKey_nativeSetLook(JNIEnv*, jobject,
                                                                         jlong handle, jfloat keyCb,
                                                                         jfloat keyCr,
                                                                         jfloat similarity,
                                                                         jfloat smoothness,
                                                                         jfloat spill,
                                                                         jboolean blueKey) {
    Keyer* keyer = fromHandle(handle);
    if (keyer == nullptr) return;
    keyer->setKeyColor(keyCb, keyCr);
    keyer->setLook(similarity, smoothness, spill, blueKey);
}

JNIEXPORT jboolean JNICALL
Java_com_soubhagyajit_awa_processing_vulkan_VulkanChromaKey_nativeSetBackground(
        JNIEnv* env, jobject, jlong handle, jbyteArray y, jbyteArray v, jbyteArray u, jint width,
        jint height) {
    Keyer* keyer = fromHandle(handle);
    if (keyer == nullptr || y == nullptr || v == nullptr || u == nullptr) return JNI_FALSE;

    const jsize lumaBytes = width * height;
    const jsize chromaBytes = lumaBytes / 4;
    if (env->GetArrayLength(y) < lumaBytes || env->GetArrayLength(v) < chromaBytes ||
        env->GetArrayLength(u) < chromaBytes) {
        return JNI_FALSE;
    }

    if (!keyer->beginFrame(static_cast<uint32_t>(width), static_cast<uint32_t>(height))) {
        return JNI_FALSE;
    }

    // The background is a still image or a constant colour, so a temporary copy is fine here: this
    // runs when the picture changes, not once per frame.
    std::vector<uint8_t> planes(static_cast<size_t>(lumaBytes) + 2 * chromaBytes);
    env->GetByteArrayRegion(y, 0, lumaBytes, reinterpret_cast<jbyte*>(planes.data()));
    env->GetByteArrayRegion(v, 0, chromaBytes, reinterpret_cast<jbyte*>(planes.data() + lumaBytes));
    env->GetByteArrayRegion(u, 0, chromaBytes,
                            reinterpret_cast<jbyte*>(planes.data() + lumaBytes + chromaBytes));
    return keyer->uploadBackground(planes.data(), planes.data() + lumaBytes,
                                   planes.data() + lumaBytes + chromaBytes)
                   ? JNI_TRUE
                   : JNI_FALSE;
}

/**
 * @return JNI_TRUE when the frame was processed. JNI_FALSE means "use the CPU keyer"; the caller
 *         treats that as a permanent failure so a broken driver cannot cost a frame every time.
 */
JNIEXPORT jboolean JNICALL
Java_com_soubhagyajit_awa_processing_vulkan_VulkanChromaKey_nativeProcess(
        JNIEnv* env, jobject, jlong handle, jbyteArray src, jbyteArray dst, jint width, jint height,
        jintArray statsOut) {
    Keyer* keyer = fromHandle(handle);
    if (keyer == nullptr || src == nullptr || dst == nullptr) return JNI_FALSE;

    const jsize bytes = width * height * 3 / 2;
    if (env->GetArrayLength(src) < bytes || env->GetArrayLength(dst) < bytes) return JNI_FALSE;

    const auto w = static_cast<uint32_t>(width);
    const auto h = static_cast<uint32_t>(height);
    if (!keyer->beginFrame(w, h)) return JNI_FALSE;

    env->GetByteArrayRegion(src, 0, bytes, reinterpret_cast<jbyte*>(keyer->srcHost()));

    // Sampling the key colour happens on the GPU, so it has to be requested before the dispatch.
    const bool sample = statsOut != nullptr;
    if (!keyer->dispatchFrame(sample)) return JNI_FALSE;

    env->SetByteArrayRegion(dst, 0, bytes, reinterpret_cast<const jbyte*>(keyer->dstHost()));

    if (sample) {
        uint32_t stats[4] = {0, 0, 0, 0};
        keyer->readStats(stats);
        const jint values[4] = {static_cast<jint>(stats[0]), static_cast<jint>(stats[1]),
                                static_cast<jint>(stats[2]), static_cast<jint>(stats[3])};
        env->SetIntArrayRegion(statsOut, 0, 4, values);
    }
    return JNI_TRUE;
}

}  // extern "C"
