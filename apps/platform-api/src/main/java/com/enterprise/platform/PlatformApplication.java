package com.enterprise.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 平台控制平面入口。
 *
 * <p>仅启用 Web、持久化和轻量定时调度；不在 Java 中运行模型 Token Loop。
 *
 * @author chomy
 **/
@SpringBootApplication
@EnableScheduling
public class PlatformApplication {
  /**
   * 启动 Spring 容器；数据库迁移和调度器生命周期由框架管理。
   **/
  public static void main(String[] args) {
    SpringApplication.run(PlatformApplication.class, args);
  }
}
