package com.admin.common.exception;

import com.admin.common.lang.R;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test void unhandledExceptionsCannotReturnInternalAddressesOrConnectionCredentials() {
        R response = handler.Exception(new SQLException("jdbc://underlying.example.com/db password=synthetic-marker"));
        assertEquals(-2, response.getCode());
        assertFalse(response.getMsg().contains("underlying.example.com"));
        assertFalse(response.getMsg().contains("synthetic-marker"));
        assertNull(response.getData());
    }

    @Test void explicitAuthorizationMessagesRemainUsable() {
        R response = handler.handleUnauthorizedException(new UnauthorizedException("没有管理此隧道的权限"));
        assertEquals(401, response.getCode());
        assertEquals("没有管理此隧道的权限", response.getMsg());
    }

    @Test void validationRetainsTheDeclaredMessageWithoutEchoingRejectedValue() throws Exception {
        var binding = new BeanPropertyBindingResult(new Object(), "input");
        binding.addError(new FieldError("input", "name", "private-rejected-marker", false, null, null, "名称不能为空"));
        MethodParameter parameter = new MethodParameter(getClass().getDeclaredMethod("endpoint", Object.class), 0);
        R response = handler.MethodArgumentNotValidException(new MethodArgumentNotValidException(parameter, binding));
        assertEquals(500, response.getCode()); assertEquals("名称不能为空", response.getMsg());
        assertFalse(response.getMsg().contains("private-rejected-marker"));
    }
    private void endpoint(Object ignored) {}
}
