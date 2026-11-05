package com.mayezer.emailorganizer.config;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;

public class OpenAIClientFactory {

    public static OpenAIClient createClient() {

        OpenAIClient client = OpenAIOkHttpClient.fromEnv();

        return client;
    }
}
