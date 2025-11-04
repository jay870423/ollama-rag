package com.example.rag.config;

import org.apache.tika.Tika;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.rag.client.OllamaClient;

/**
 * 作者: liangyajie
 * 联系方式: 695274107@qq.com
 * AI相关配置类
 */

@Configuration
public class AiConfig {

    @Bean
    public OllamaClient ollamaClient() {
        // 创建Ollama客户端，配置为使用本地Ollama服务
        return new OllamaClient("http://localhost:11434");
    }

    @Bean
    public Tika tika() {
        // Tika文档解析器，支持多种文件格式
        return new Tika();
    }
}