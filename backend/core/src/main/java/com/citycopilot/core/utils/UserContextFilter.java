package com.citycopilot.core.utils;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 仅限定 Servlet 线程身份作用域；会话读取仍在 MVC 请求预算准入之后执行。 */
public final class UserContextFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        UserHolder.removeUser();
        try {
            chain.doFilter(request, response);
        } finally {
            UserHolder.removeUser();
        }
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() { return false; }

    @Override
    protected boolean shouldNotFilterErrorDispatch() { return false; }
}
