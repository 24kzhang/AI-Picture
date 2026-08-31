package com.zys.backend.manager.upload;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.zys.backend.exception.BusinessException;
import com.zys.backend.exception.ErrorCode;
import com.zys.backend.exception.ThrowUtils;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;

/**
 * URL 图片上传。包含大小限制、超时、重定向限制和 SSRF 防护。
 */
@Service
public class UrlPictureUpload extends PictureUploadTemplate {

    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;

    @Override
    protected void validPicture(Object inputSource) {
        String fileUrl = (String) inputSource;
        ThrowUtils.throwIf(StrUtil.isBlank(fileUrl), ErrorCode.PARAMS_ERROR, "文件地址为空");
        validateRemoteUrl(toUrl(fileUrl));
    }

    @Override
    protected String getOriginFilename(Object inputSource) {
        String fileUrl = (String) inputSource;
        try {
            String fileName = FileUtil.getName(new URL(fileUrl).getPath());
            return StrUtil.isBlank(fileName) ? "image.jpg" : fileName;
        } catch (MalformedURLException ignored) {
            return "image.jpg";
        }
    }

    @Override
    protected void processFile(Object inputSource, File file) throws Exception {
        URL current = toUrl((String) inputSource);
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            validateRemoteUrl(current);
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(15000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", "LocalCloudGallery/1.0");
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                ThrowUtils.throwIf(StrUtil.isBlank(location), ErrorCode.PARAMS_ERROR, "图片重定向地址为空");
                current = new URL(current, location);
                continue;
            }
            ThrowUtils.throwIf(status < 200 || status >= 300,
                    ErrorCode.PARAMS_ERROR, "远程图片下载失败，状态码：" + status);
            String contentType = connection.getContentType();
            ThrowUtils.throwIf(StrUtil.isBlank(contentType) || !contentType.toLowerCase().startsWith("image/"),
                    ErrorCode.PARAMS_ERROR, "远程地址不是图片");
            long contentLength = connection.getContentLengthLong();
            ThrowUtils.throwIf(contentLength > MAX_FILE_SIZE,
                    ErrorCode.PARAMS_ERROR, "远程图片不能超过 10MB");
            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(file)) {
                byte[] buffer = new byte[8192];
                long total = 0;
                int length;
                while ((length = input.read(buffer)) != -1) {
                    total += length;
                    ThrowUtils.throwIf(total > MAX_FILE_SIZE,
                            ErrorCode.PARAMS_ERROR, "远程图片不能超过 10MB");
                    output.write(buffer, 0, length);
                }
            } finally {
                connection.disconnect();
            }
            return;
        }
        throw new BusinessException(ErrorCode.PARAMS_ERROR, "远程图片重定向次数过多");
    }

    private URL toUrl(String value) {
        try {
            URL url = new URL(value);
            ThrowUtils.throwIf(!"http".equalsIgnoreCase(url.getProtocol())
                            && !"https".equalsIgnoreCase(url.getProtocol()),
                    ErrorCode.PARAMS_ERROR, "仅支持 HTTP 或 HTTPS 图片地址");
            return url;
        } catch (MalformedURLException e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "文件地址格式不正确");
        }
    }

    private void validateRemoteUrl(URL url) {
        String host = url.getHost();
        ThrowUtils.throwIf(StrUtil.isBlank(host), ErrorCode.PARAMS_ERROR, "图片地址缺少主机名");
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                boolean unsafe = address.isAnyLocalAddress()
                        || address.isLoopbackAddress()
                        || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress()
                        || address.isMulticastAddress();
                ThrowUtils.throwIf(unsafe, ErrorCode.PARAMS_ERROR, "不允许访问本机或内网地址");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "图片域名无法解析");
        }
    }
}
