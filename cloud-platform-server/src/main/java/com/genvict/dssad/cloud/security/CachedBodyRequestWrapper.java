package com.genvict.dssad.cloud.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 可重复读取请求体的包装器（签名校验必需）。
 *
 * <p><b>为什么不能用 Spring 的 {@code ContentCachingRequestWrapper}？</b>
 * 它的 {@code getInputStream()} 每次返回的都是「包装在原始流之上」的新实例，
 * 第一次读取后原始流已到末尾，下游 Controller 再读会得到<b>空 body</b>（典型踩坑）。
 *
 * <p>本实现把请求体一次性读入 {@code byte[]}，每次 {@code getInputStream()} /
 * {@code getReader()} 都返回基于该数组的新流，因此可以被「过滤器校验一次 + Controller 再读一次」，
 * 且天然线程安全（数组只读）。
 *
 * <p>代价：请求体会驻留内存。平台对外接口的请求体都很小（文档 9.2：HTTP 请求体 ≤ 10MB，
 * 而视频上传走 multipart 且不参与签名），因此可以接受。为保证安全，
 * 只对 {@code application/json} 的请求做缓存。
 */
public class CachedBodyRequestWrapper extends HttpServletRequestWrapper {

    private final byte[] body;

    public CachedBodyRequestWrapper(HttpServletRequest request) throws IOException {
        super(request);
        this.body = request.getInputStream().readAllBytes();
    }

    /** 获取缓存的请求体字节。 */
    public byte[] getBody() {
        return body;
    }

    /** 获取缓存的请求体文本（UTF-8）。 */
    public String getBodyAsString() {
        return new String(body, StandardCharsets.UTF_8);
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream buffer = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return buffer.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {
                // 同步读取，不使用异步 IO，无需通知回调
            }

            @Override
            public int read() {
                return buffer.read();
            }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }
}
