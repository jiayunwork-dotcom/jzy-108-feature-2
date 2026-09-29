package com.bearingfreq.validation;

/**
 * 输入参数不合法时抛出，携带具体原因，由接口层转换为 400 响应。
 */
public class InvalidBearingInputException extends RuntimeException {

    public InvalidBearingInputException(String reason) {
        super(reason);
    }
}
