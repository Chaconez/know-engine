package com.chaconez.knowengine;

import com.chaconez.knowengine.rag.modules.KnowEngineQueryTransformer;
import org.springframework.beans.BeansException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.lang.NonNull;

@SpringBootApplication
public class KnowEngineApplication implements ApplicationContextAware{

    public static void main(String[] args) {
        SpringApplication.run(KnowEngineApplication.class, args);
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        KnowEngineQueryTransformer.setApplicationContext(applicationContext);
    }
}
