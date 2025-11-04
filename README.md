# Ollama-RAG 项目使用说明

## 项目简介

**Ollama-RAG** 是一个基于本地 Ollama 模型的检索增强生成（Retrieval-Augmented Generation）系统，支持文档上传、智能检索和问答功能。该系统允许用户上传文档，系统会自动处理文档内容并生成嵌入向量，然后在用户提问时检索相关内容并结合大语言模型生成精准回答。

## 功能特性

- **文档上传与解析**：支持多种格式文档的上传和内容提取
- **向量嵌入与存储**：将文档内容转换为向量并存储，以便快速相似度检索
- **智能问答**：基于检索到的相关文档内容生成精准回答
- **基于本地 Ollama**：完全本地运行，保护数据隐私
- **RESTful API**：提供标准化的 HTTP 接口，便于集成

## 技术栈

- **后端框架**：Spring Boot 3.5.7
- **Java 版本**：JDK 21
- **向量存储**：自定义简单向量存储实现
- **文档解析**：Apache Tika
- **HTTP 客户端**：Retrofit 2
- **缓存工具**：Guava
- **Git 集成**：git-commit-id-plugin

## 环境要求

- JDK 21 或更高版本
- Maven 3.6+ 或 Gradle 7.0+
- Ollama 服务（本地运行）
- 足够的内存用于模型加载和向量计算

## 安装与配置

### 1. 克隆项目

```bash
git clone <项目仓库地址>
cd ollama-rag
```

### 2. 配置 Ollama

确保您已经安装并运行了 Ollama 服务。本项目默认配置使用 `qwen2.5:1.5b` 模型。您可以在 `application.properties` 文件中修改模型配置：

```properties
# Ollama 模型配置
spring.ai.openai.chat.options.model=qwen2.5:1.5b
```

### 3. 编译项目

```bash
mvn clean compile
```

### 4. 运行项目

```bash
mvn spring-boot:run
```

或者使用打包后的 jar 文件：

```bash
mvn clean package
java -jar target/ollama-rag-0.0.1-SNAPSHOT.jar
```

## API 接口文档

### 1. 上传文档

**URL**: `/api/documents`
**Method**: `POST`
**Content-Type**: `multipart/form-data`

**参数**:
- `file`: 要上传的文档文件（支持常见文档格式）

**响应**:
```json
{
  "id": "文档ID",
  "title": "文档标题",
  "content": "文档内容摘要",
  "uploadTime": "上传时间",
  "status": "处理状态"
}
```

### 2. 查询文档

**URL**: `/api/query`
**Method**: `POST`
**Content-Type**: `application/json`

**请求体**:
```json
{
  "question": "用户问题",
  "topK": 3  // 返回最相关的前K个文档片段
}
```

**响应**:
```json
{
  "answer": "生成的回答内容",
  "sources": [
    {
      "documentId": "相关文档ID",
      "content": "相关内容片段",
      "similarityScore": 0.95
    }
  ],
  "processingTime": 2500
}
```

### 3. 获取所有文档

**URL**: `/api/documents`
**Method**: `GET`

**响应**:
```json
[
  {
    "id": "文档ID",
    "title": "文档标题",
    "uploadTime": "上传时间",
    "contentLength": 1024
  }
]
```

### 4. 删除文档

**URL**: `/api/documents/{documentId}`
**Method**: `DELETE`

**响应**:
```json
{
  "success": true,
  "message": "文档删除成功"
}
```

## 项目结构

```
ollama-rag/
├── src/
│   ├── main/
│   │   ├── java/com/example/rag/
│   │   │   ├── config/        # 配置类
│   │   │   ├── controller/     # REST控制器
│   │   │   ├── model/          # 数据模型
│   │   │   ├── service/        # 业务逻辑
│   │   │   └── TestRagApplication.java  # 应用入口
│   │   └── resources/
│   │       ├── static/         # 静态资源
│   │       └── application.properties  # 应用配置
│   └── test/                   # 测试代码
├── pom.xml                     # Maven配置
└── README.md                   # 项目说明
```

## 关键类说明

### TestRagApplication.java
应用程序入口类，负责启动Spring Boot应用。

### RagController.java
处理HTTP请求的控制器，提供文档上传、查询等API接口。

### RagService.java
核心业务服务类，处理文档上传、查询和向量检索的主要逻辑。

### OllamaClient.java
Ollama客户端，用于与本地Ollama模型进行交互。

### SimpleVectorStore.java
简单向量存储实现，用于文档嵌入和相似度检索。

### Document.java
文档模型类，用于存储文档内容、元数据和嵌入向量。

## 常见问题与排查

### 1. Ollama 连接失败
确保 Ollama 服务已启动，并且配置的模型名称正确。可以通过访问 `http://localhost:11434` 验证 Ollama 服务是否正常运行。

### 2. 文档解析错误
检查上传的文档格式是否受支持。当前系统支持常见的文档格式如 PDF、DOCX、TXT 等。

### 3. 内存不足
处理大型文档或多个并发请求时可能会遇到内存不足的情况。可以通过增加 JVM 内存参数解决：

```bash
java -Xmx4g -jar target/ollama-rag-0.0.1-SNAPSHOT.jar
```

## 性能优化建议

1. **调整向量存储实现**：对于生产环境，建议使用专业的向量数据库如 Pinecone、Milvus 或 Weaviate
2. **增加缓存层**：缓存频繁查询的结果，减少重复计算
3. **批量处理**：处理大量文档时使用批量处理机制
4. **异步处理**：文档上传和处理过程改为异步，提高系统响应速度

## 作者信息

作者：liangyajie
联系方式：695274107@qq.com

## 许可证

MIT License
