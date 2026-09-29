package com.bearingfreq.api;

import com.bearingfreq.catalog.CatalogEntryNotFoundException;
import com.bearingfreq.validation.InvalidBearingInputException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一错误响应：把校验失败、档案缺失等转换为带原因的 HTTP 错误（RFC 7807 ProblemDetail）。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InvalidBearingInputException.class)
    public ProblemDetail handleInvalidInput(InvalidBearingInputException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("输入参数不合法");
        problem.setProperty("reason", ex.getMessage());
        return problem;
    }

    @ExceptionHandler(CatalogEntryNotFoundException.class)
    public ProblemDetail handleCatalogEntryMissing(CatalogEntryNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("轴承档不存在");
        problem.setProperty("reason", ex.getMessage());
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException ex) {
        String reason = "请求体不是合法 JSON 或字段类型不匹配";
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, reason);
        problem.setTitle("请求体无法解析");
        problem.setProperty("reason", reason);
        return problem;
    }
}
