// 作者: liangyajie
// 联系方式: 695274107@qq.com
// Vue应用主脚本 - 确保Vue已加载
if (typeof Vue !== 'undefined') {
    const { createApp, ref, computed, onMounted } = Vue;

    const app = createApp({
    setup() {
        // 状态管理
        const uploadedFiles = ref([]);
        const queryText = ref('');
        const responseText = ref('');
        const isUploading = ref(false);
        const isQuerying = ref(false);
        const isClearing = ref(false);
        const showSuccessToast = ref(false);
        const toastMessage = ref('');
        const activeTab = ref('upload'); // upload, query, manage
        const isFileInputDisabled = ref(false);
        const errorMessage = ref('');
        const showErrorMessage = ref(false);
        const documentCount = ref(0);
        const useStreamResponse = ref(true); // 默认使用流式响应
        const isStreaming = ref(false);

        // 计算属性 - 移除isStreaming条件，避免流式查询时按钮被禁用
        const isQueryDisabled = computed(() => !queryText.value.trim() || isQuerying.value);
        const isUploadDisabled = computed(() => isUploading.value || isFileInputDisabled.value);
        const isClearDisabled = computed(() => isClearing.value || documentCount.value === 0);

        // 显示成功提示
        const showToast = (message) => {
            toastMessage.value = message;
            showSuccessToast.value = true;
            setTimeout(() => {
                showSuccessToast.value = false;
            }, 3000);
        };

        // 显示错误提示
        const showError = (message) => {
            errorMessage.value = message;
            showErrorMessage.value = true;
            setTimeout(() => {
                showErrorMessage.value = false;
            }, 5000);
        };

        // 上传文档
        const uploadDocument = async (event) => {
            const file = event.target.files[0];
            if (!file) return;

            isUploading.value = true;
            isFileInputDisabled.value = true;
            const formData = new FormData();
            formData.append('file', file);

            try {
                const response = await axios.post('/api/upload', formData, {
                    headers: {
                        'Content-Type': 'multipart/form-data'
                    },
                    onUploadProgress: (progressEvent) => {
                        // 可以在这里实现上传进度条
                    }
                });

                // 上传成功后重新加载文件列表
                await loadFiles();
                showToast(`文件 ${file.name} 上传成功！`);
                event.target.value = ''; // 清空文件输入
            } catch (error) {
                console.error('上传失败:', error);
                showError('文件上传失败，请重试。错误：' + (error.response?.data?.message || error.message));
            } finally {
                // 确保无论成功还是失败，状态都会被重置
                console.log('重置上传状态');
                isUploading.value = false;
                isFileInputDisabled.value = false;
            }
        };
        
        // 加载文件列表
        const loadFiles = async () => {
            try {
                const response = await axios.get('/api/files');
                uploadedFiles.value = response.data.map(file => ({
                    id: file.id,
                    name: file.name,
                    // 尝试从后端获取文件大小信息，如果没有则保持为0
                    size: file.size || 0,
                    type: file.type || '未知类型',
                    uploadedAt: file.uploadedAt ? new Date(file.uploadedAt).toLocaleString() : new Date().toLocaleString()
                }));
                documentCount.value = uploadedFiles.value.length;
            } catch (error) {
                console.error('加载文件列表失败:', error);
                uploadedFiles.value = [];
                documentCount.value = 0;
            }
        };
        
        // 删除单个文件
        const deleteFile = async (fileId, fileName) => {
            console.log('开始删除文件:', fileName, 'ID:', fileId);
            // 使用更明确的确认对话框
            const confirmed = confirm(`⚠️ 确认删除 ⚠️\n\n确定要删除文件 "${fileName}" 吗？\n此操作不可撤销，文件将被永久删除。`);
            console.log('用户确认状态:', confirmed);
            
            if (!confirmed) {
                console.log('用户取消了删除操作');
                return;
            }

            try {
                console.log('用户已确认，执行删除操作');
                await axios.delete(`/api/files/${fileId}`);
                // 重新加载文件列表
                await loadFiles();
                showToast(`文件 ${fileName} 已成功删除`);
            } catch (error) {
                console.error('删除文件失败:', error);
                showError('删除文件失败，请重试。错误：' + (error.response?.data?.message || error.message));
            }
        };

        // 查询文档（支持流式和非流式）
        const queryDocument = async () => {
            if (!queryText.value.trim()) {
                showError('请输入查询内容');
                return;
            }

            isQuerying.value = true;
            responseText.value = '';

            try {
                if (useStreamResponse.value) {
                    // 使用流式响应 - 直接使用Fetch API
                    await fetchStreamWithFetchAPI();
                } else {
                    // 使用非流式响应
                    const response = await axios.post('/api/query', {
                        query: queryText.value.trim()
                    });
                    responseText.value = response.data || '暂无相关信息';
                }
            } catch (error) {
                console.error('查询失败:', error);
                showError('查询失败，请重试。错误：' + (error.response?.data?.message || error.message));
                responseText.value = '查询过程中发生错误';
            } finally {
                isQuerying.value = false;
                isStreaming.value = false;
            }
        };
        
        // 移除EventSource实现，因为它不支持POST请求
        // 直接使用fetch API实现流式响应
        
        // 使用Fetch API实现流式响应
        const fetchStreamWithFetchAPI = async () => {
            isStreaming.value = true;
            
            try {
                const controller = new AbortController();
                const timeoutId = setTimeout(() => controller.abort(), 300000); // 5分钟超时
                
                const response = await fetch('/api/query/stream', {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                        'Accept': 'text/event-stream'
                    },
                    body: JSON.stringify({ query: queryText.value.trim() }),
                    signal: controller.signal
                });
                
                clearTimeout(timeoutId);
                
                if (!response.ok) {
                    throw new Error('HTTP error! status: ' + response.status);
                }
                
                if (!response.body) {
                    throw new Error('Response body is null');
                }
                
                const reader = response.body.getReader();
                const decoder = new TextDecoder('utf-8');
                let buffer = '';
                let lastUpdateTime = 0;
                const UPDATE_INTERVAL = 50; // 每50ms更新一次UI
                
                while (true) {
                    const { done, value } = await reader.read();
                    
                    if (done) {
                        // 处理最终缓冲区内容
                        if (buffer.trim()) {
                            const { processed, remaining } = processBuffer(buffer);
                            if (processed) {
                                responseText.value += processed;
                            }
                        }
                        break;
                    }
                    
                    // 解码新获取的数据
                    const chunk = decoder.decode(value, { stream: true });
                    buffer += chunk;
                    
                    // 更频繁地处理和更新UI，提供更流畅的体验
                    const now = Date.now();
                    if (now - lastUpdateTime > UPDATE_INTERVAL) {
                        const { processed, remaining } = processBuffer(buffer);
                        if (processed) {
                            responseText.value += processed;
                            buffer = remaining; // 只保留未处理的内容
                            lastUpdateTime = now;
                        }
                    }
                }
                
                // 处理缓冲区并返回已处理的内容和剩余内容
                function processBuffer(buffer) {
                    let processed = '';
                    let remaining = '';
                    
                    try {
                        // 尝试按SSE格式处理，避免引入不必要的换行
                        const parts = buffer.split('data: ');
                        for (let i = 1; i < parts.length; i++) {
                            // 移除任何换行符和空白字符，只保留实际内容
                            let content = parts[i].trim();
                            // 如果内容不为空，添加到已处理部分
                            if (content) {
                                processed += content;
                            }
                        }
                    } catch (error) {
                        console.error('处理流式响应时出错:', error);
                        // 发生错误时，尝试直接返回缓冲区内容
                        processed = buffer.trim();
                    }
                    
                    return { processed, remaining };
                }
            } finally {
                isStreaming.value = false;
            }
        };

        // 清除所有文档
        const clearDocuments = async () => {
            if (!confirm('确定要清除所有已上传的文档吗？此操作不可撤销。')) {
                return;
            }

            isClearing.value = true;

            try {
                await axios.delete('/api/clear');
                uploadedFiles.value = [];
                documentCount.value = 0;
                showToast('所有文档已成功清除');
            } catch (error) {
                console.error('清除失败:', error);
                showError('清除文档失败，请重试。错误：' + (error.response?.data?.message || error.message));
            } finally {
                isClearing.value = false;
            }
        };
        
        // 页面加载时初始化
        loadFiles();

        // 格式化文件大小
        const formatFileSize = (bytes) => {
            if (bytes === 0) return '0 Bytes';
            const k = 1024;
            const sizes = ['Bytes', 'KB', 'MB', 'GB'];
            const i = Math.floor(Math.log(bytes) / Math.log(k));
            return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
        };

        // 切换标签页
        const switchTab = (tab) => {
            activeTab.value = tab;
            // 平滑滚动到页面顶部
            window.scrollTo({ top: 0, behavior: 'smooth' });
        };

        // 检查文档数量
        const checkDocumentCount = async () => {
            // 这里可以添加一个API调用来获取实际的文档数量
            // 暂时使用本地状态
        };

        // 组件挂载时执行
        onMounted(() => {
            checkDocumentCount();
        });

        // 按Enter键执行查询
        const handleQueryKeyDown = (event) => {
            if (event.key === 'Enter' && !event.shiftKey) {
                event.preventDefault();
                queryDocument();
            }
        };

        return {
            // 状态
            uploadedFiles,
            queryText,
            responseText,
            isUploading,
            isQuerying,
            isStreaming,
            isClearing,
            showSuccessToast,
            toastMessage,
            activeTab,
            isFileInputDisabled,
            errorMessage,
            showErrorMessage,
            documentCount,
            useStreamResponse,
            
            // 计算属性
            isQueryDisabled,
            isUploadDisabled,
            isClearDisabled,
            
            // 方法
            uploadDocument,
            queryDocument,
            clearDocuments,
            deleteFile, // 添加删除单个文件方法
            formatFileSize,
            switchTab,
            handleQueryKeyDown
        };
    },
    template: `
        <div class="space-y-8">
            <!-- 头部 -->
            <header class="text-center">
                <h1 class="text-[clamp(2rem,5vw,3rem)] font-bold text-dark mb-2 text-shadow">
                    智能文档助手
                </h1>
                <p class="text-gray-600 text-lg max-w-2xl mx-auto">
                    基于本地Ollama模型的RAG系统，支持文档上传、智能检索和问答功能
                </p>
            </header>

            <!-- 导航标签 -->
            <nav class="bg-white rounded-xl shadow-md p-1 flex justify-center gap-2">
                <button
                    v-for="tab in [{ id: 'upload', name: '文档上传', icon: 'fa-upload' }, { id: 'query', name: '智能问答', icon: 'fa-search' }, { id: 'manage', name: '文档管理', icon: 'fa-folder' }]"
                    :key="tab.id"
                    @click="switchTab(tab.id)"
                    :class="[
                        'flex items-center gap-2 px-5 py-3 rounded-lg transition-all-300 font-medium',
                        activeTab === tab.id 
                            ? 'bg-primary text-white shadow-lg transform -translate-y-1' 
                            : 'text-gray-600 hover:bg-gray-100'
                    ]"
                >
                    <i :class="['fa', tab.icon]"></i>
                    <span>{{ tab.name }}</span>
                </button>
            </nav>

            <!-- 主要内容 -->
            <main>
                <!-- 上传标签页 -->
                <div v-show="activeTab === 'upload'" class="bg-white rounded-2xl shadow-xl p-8 animate-fadeIn">
                    <div class="border-2 border-dashed border-gray-300 rounded-xl p-12 text-center transition-all-300 hover:border-primary cursor-pointer group" @click="$refs.fileInput.click()">
                        <input
                            ref="fileInput"
                            type="file"
                            @change="uploadDocument"
                            :disabled="isUploadDisabled"
                            class="hidden"
                            accept=".txt,.pdf,.doc,.docx,.md"
                        />
                        <div class="text-primary mb-4">
                            <i class="fa fa-cloud-upload text-6xl group-hover:scale-110 transition-transform"></i>
                        </div>
                        <h3 class="text-xl font-semibold mb-2">拖放文件到此处或点击上传</h3>
                        <p class="text-gray-500 mb-4">支持 TXT, PDF, DOC, DOCX, MD 格式的文件</p>
                        <button 
                            type="button" 
                            @click.stop="$refs.fileInput.click()"
                            :disabled="isUploadDisabled"
                            class="bg-primary hover:bg-primary/90 text-white px-6 py-3 rounded-lg font-medium transition-all-300 shadow-md hover:shadow-lg disabled:opacity-50 disabled:cursor-not-allowed"
                        >
                            <span v-if="!isUploading">选择文件</span>
                            <span v-else>上传中...</span>
                        </button>
                    </div>

                    <!-- 最近上传文件列表 -->
                    <div v-if="uploadedFiles.length > 0" class="mt-8">
                        <h3 class="text-lg font-semibold mb-4">最近上传的文件</h3>
                        <div class="space-y-3 max-h-60 overflow-y-auto scrollbar-hide pr-2">
                            <div v-for="file in uploadedFiles.slice(-5)" :key="file.id" class="bg-gray-50 p-4 rounded-lg border border-gray-200 flex justify-between items-center hover:bg-gray-100 transition-all-300">
                                <div>
                                    <div class="font-medium">{{ file.name }}</div>
                                    <div class="text-sm text-gray-500">
                                        {{ formatFileSize(file.size) }} · {{ file.uploadedAt }}
                                    </div>
                                </div>
                                <i class="fa fa-check-circle text-secondary"></i>
                            </div>
                        </div>
                    </div>
                </div>

                <!-- 查询标签页 -->
                <div v-show="activeTab === 'query'" class="bg-white rounded-2xl shadow-xl p-8 animate-fadeIn">
                    <div class="mb-6">
                        <label for="queryInput" class="block text-sm font-medium text-gray-700 mb-2">输入您的问题</label>
                        <div class="relative">
                            <textarea
                                id="queryInput"
                                v-model="queryText"
                                @keydown="handleQueryKeyDown"
                                :disabled="isQuerying"
                                placeholder="请输入您想查询的内容..."
                                class="w-full px-4 py-3 rounded-xl border border-gray-300 focus:ring-2 focus:ring-primary/30 focus:border-primary outline-none transition-all-300 resize-none min-h-[120px]"
                            ></textarea>
                            <div class="absolute right-3 bottom-3 flex gap-2">
                            <!-- 流式响应开关 -->
                            <label class="inline-flex items-center cursor-pointer bg-gray-100 px-3 py-1 rounded-lg text-sm" @click.stop>
                                <input 
                                    type="checkbox" 
                                    v-model="useStreamResponse" 
                                    class="sr-only"
                                >
                                <div class="relative w-8 h-4 bg-gray-300 rounded-full transition-colors" :class="{ 'bg-primary': useStreamResponse }">
                                    <div class="absolute top-[2px] left-[2px] bg-white border border-gray-300 rounded-full h-3 w-3 transition-all transform" :class="{ 'translate-x-full': useStreamResponse }"></div>
                                </div>
                                <span class="ml-2 text-xs font-medium text-gray-700">流式</span>
                            </label>
                            
                            <!-- 发送按钮 -->
                            <button
                                @click="queryDocument"
                                :disabled="isQueryDisabled"
                                class="bg-primary hover:bg-primary/90 text-white px-4 py-2 rounded-lg font-medium transition-all-300 shadow disabled:opacity-50 disabled:cursor-not-allowed"
                            >
                                <i v-if="!isQuerying" class="fa fa-paper-plane mr-1"></i>
                                <i v-else class="fa fa-spinner fa-spin mr-1"></i>
                                {{ isQuerying ? (useStreamResponse ? '流式生成中...' : '查询中...') : '发送' }}
                            </button>
                        </div>
                        </div>
                    </div>

                    <!-- 回答结果 -->
                    <div v-if="responseText || isStreaming" class="bg-gray-50 rounded-xl p-6 border border-gray-200">
                        <h3 class="text-lg font-semibold mb-3 flex items-center">
                            <i class="fa fa-robot text-accent mr-2"></i>
                            智能回答
                            <span v-if="isStreaming" class="ml-2 text-xs bg-blue-100 text-blue-800 px-2 py-1 rounded-full">
                                <i class="fa fa-circle-o-notch fa-spin mr-1"></i>生成中...
                            </span>
                        </h3>
                        <div class="text-gray-800 whitespace-pre-wrap">
                            {{ responseText }}
                            <span v-if="isStreaming" class="inline-block w-2 h-2 bg-gray-400 rounded-full animate-pulse ml-1"></span>
                            <span v-if="isStreaming" class="inline-block w-2 h-2 bg-gray-400 rounded-full animate-pulse ml-1 delay-150"></span>
                            <span v-if="isStreaming" class="inline-block w-2 h-2 bg-gray-400 rounded-full animate-pulse ml-1 delay-300"></span>
                        </div>
                    </div>

                    <!-- 提示信息 -->
                    <div v-if="!responseText && !isQuerying && queryText" class="text-center py-8 text-gray-500">
                        <i class="fa fa-lightbulb-o text-4xl mb-2 text-yellow-400"></i>
                        <p>点击发送按钮获取智能回答</p>
                    </div>
                </div>

                <!-- 管理标签页 -->
                <div v-show="activeTab === 'manage'" class="bg-white rounded-2xl shadow-xl p-8 animate-fadeIn">
                    <div class="flex justify-between items-center mb-6">
                        <h3 class="text-xl font-semibold">文档管理</h3>
                        <button
                            @click="clearDocuments"
                            :disabled="isClearDisabled"
                            class="bg-red-500 hover:bg-red-600 text-white px-4 py-2 rounded-lg font-medium transition-all-300 shadow disabled:opacity-50 disabled:cursor-not-allowed flex items-center gap-1"
                        >
                            <i v-if="!isClearing" class="fa fa-trash"></i>
                            <i v-else class="fa fa-spinner fa-spin"></i>
                            清除所有文档
                        </button>
                    </div>

                    <!-- 文档统计 -->
                    <div class="bg-gradient-to-r from-blue-50 to-indigo-50 p-4 rounded-xl mb-6">
                        <div class="text-sm text-gray-600 mb-1">当前已上传文档数量</div>
                        <div class="text-3xl font-bold text-primary">{{ documentCount }}</div>
                    </div>

                    <!-- 文档列表 -->
                    <div v-if="uploadedFiles.length > 0" class="space-y-3 max-h-[500px] overflow-y-auto scrollbar-hide pr-2">
                        <div v-for="file in uploadedFiles" :key="file.id" class="bg-gray-50 p-4 rounded-lg border border-gray-200 hover:bg-gray-100 transition-all-300">
                            <div class="flex justify-between items-center mb-2">
                                <div class="font-medium">{{ file.name }}</div>
                                <button
                                    @click="deleteFile(file.id, file.name)"
                                    class="bg-red-50 text-red-600 hover:bg-red-100 p-2 rounded-md transition-colors focus:outline-none focus:ring-2 focus:ring-red-300"
                                    title="删除文件"
                                >
                                    <i class="fa fa-trash text-lg"></i>
                                </button>
                            </div>
                            <div class="text-sm text-gray-500 grid grid-cols-3 gap-2">
                                <div><i class="fa fa-file-o mr-1"></i> {{ file.type || '未知类型' }}</div>
                                <div><i class="fa fa-hdd-o mr-1"></i> {{ formatFileSize(file.size) }}</div>
                                <div><i class="fa fa-clock-o mr-1"></i> {{ file.uploadedAt }}</div>
                            </div>
                        </div>
                    </div>

                    <!-- 空状态 -->
                    <div v-else class="text-center py-12 text-gray-500">
                        <i class="fa fa-folder-open-o text-5xl mb-4"></i>
                        <p class="text-lg">暂无上传的文档</p>
                        <button @click="switchTab('upload')" class="mt-4 text-primary hover:text-primary/80 font-medium flex items-center gap-1 mx-auto">
                            <i class="fa fa-arrow-right"></i>
                            去上传文档
                        </button>
                    </div>
                </div>
            </main>

            <!-- 页脚 -->
            <footer class="text-center text-gray-500 text-sm mt-12">
                <p>基于本地Ollama模型和Spring Boot开发的智能文档助手</p>
                <p class="mt-1">© 2025 智能文档助手 - 本地RAG系统</p>
            </footer>

            <!-- 成功提示 -->
            <div 
                v-show="showSuccessToast" 
                class="fixed top-5 right-5 bg-green-500 text-white px-6 py-3 rounded-lg shadow-lg transform transition-all duration-300 flex items-center gap-2 z-50"
            >
                <i class="fa fa-check-circle"></i>
                <span>{{ toastMessage }}</span>
            </div>

            <!-- 错误提示 -->
            <div 
                v-show="showErrorMessage" 
                class="fixed top-5 right-5 bg-red-500 text-white px-6 py-3 rounded-lg shadow-lg transform transition-all duration-300 flex items-center gap-2 z-50"
            >
                <i class="fa fa-exclamation-circle"></i>
                <span>{{ errorMessage }}</span>
            </div>
        </div>
    `
})

    // 挂载应用
    app.mount('#app');
} else {
    console.error('Vue未加载，请检查Vue CDN链接');
    // 显示错误信息给用户
    document.addEventListener('DOMContentLoaded', function() {
        const appElement = document.getElementById('app');
        if (appElement) {
            appElement.innerHTML = `
                <div class="bg-red-50 border border-red-400 text-red-700 px-4 py-3 rounded mt-8">
                    <strong class="font-bold">错误：</strong>
                    <span class="block sm:inline">Vue.js未加载成功，请刷新页面重试或检查网络连接。</span>
                </div>
            `;
        }
    });
}

// 添加动画样式
const style = document.createElement('style');
style.textContent = `
    @keyframes fadeIn {
        from {
            opacity: 0;
            transform: translateY(20px);
        }
        to {
            opacity: 1;
            transform: translateY(0);
        }
    }
    
    .animate-fadeIn {
        animation: fadeIn 0.3s ease-out;
    }
`;
document.head.appendChild(style);