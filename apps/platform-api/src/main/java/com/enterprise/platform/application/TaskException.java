package com.enterprise.platform.application;

/**
 * 携带安全错误码和 HTTP 状态的应用异常。
 *
 * <p>message 必须是平台定义的错误码，不能直接使用可能含凭证的第三方异常文本。
 *
 * @author chomy
 **/
public class TaskException extends RuntimeException {
  private final int status;

  /**
   * 保存 HTTP 状态和安全错误码；底层异常需要在适配边界先做脱敏。
   **/
  public TaskException(int status, String code) {
    super(code);
    this.status = status;
  }

  /**
   * 返回协议层使用的 HTTP 状态，不包含底层连接细节。
   **/
  public int status() {
    return status;
  }
}
