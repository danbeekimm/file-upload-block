package com.study.fileupload;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FileUploadBlockApplication {

    public static void main(String[] args) {
        SpringApplication.run(FileUploadBlockApplication.class, args);
    }
}
