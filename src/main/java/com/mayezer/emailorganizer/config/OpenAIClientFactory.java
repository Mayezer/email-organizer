package com.mayezer.emailorganizer.config;

import java.time.Duration;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;

public class OpenAIClientFactory {

    public static OpenAIClient createClient() {

        OpenAIClient client = OpenAIOkHttpClient.fromEnv();

        return client;
    }

    public static OpenAIClient createBackgroundClient() {
        return OpenAIOkHttpClient.builder().fromEnv().timeout(Duration.ofSeconds(25)).maxRetries(0).build();
    }
}
