package cn.keking.service.impl;

import cn.keking.config.ConfigConstants;
import cn.keking.model.FileAttribute;
import cn.keking.model.ReturnResponse;
import cn.keking.service.FileHandlerService;
import cn.keking.service.FilePreview;
import cn.keking.service.OfficeToPdfService;
import cn.keking.utils.DownloadUtils;
import cn.keking.utils.KkFileUtils;
import cn.keking.utils.OfficeUtils;
import cn.keking.utils.WebUtils;
import cn.keking.web.filter.BaseUrlFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.poi.EncryptedDocumentException;
import org.jodconverter.core.office.OfficeException;
import org.springframework.stereotype.Service;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Created by kl on 2018/1/17.
 * Content :处理office文件
 */
@Service
public class OfficeFilePreviewImpl implements FilePreview {

    public static final String OFFICE_PREVIEW_TYPE_IMAGE = "image";
    public static final String OFFICE_PREVIEW_TYPE_ALL_IMAGES = "allImages";
    private static final String OFFICE_PASSWORD_MSG = "password";

    private final FileHandlerService fileHandlerService;
    private final OfficeToPdfService officeToPdfService;
    private final OtherFilePreviewImpl otherFilePreview;

    public OfficeFilePreviewImpl(FileHandlerService fileHandlerService, OfficeToPdfService officeToPdfService, OtherFilePreviewImpl otherFilePreview) {
        this.fileHandlerService = fileHandlerService;
        this.officeToPdfService = officeToPdfService;
        this.otherFilePreview = otherFilePreview;
    }

    @Override
    public String filePreviewHandle(String url, Model model, FileAttribute fileAttribute) {
        // 预览Type，参数传了就取参数的，没传取系统默认
        String officePreviewType = fileAttribute.getOfficePreviewType();
        boolean userToken = fileAttribute.getUsePasswordCache();
        String baseUrl = BaseUrlFilter.getBaseUrl();
        String suffix = fileAttribute.getSuffix();  //获取文件后缀
        String fileName = fileAttribute.getName(); //获取文件原始名称
        String filePassword = fileAttribute.getFilePassword(); //获取密码
        boolean forceUpdatedCache = fileAttribute.forceUpdatedCache();  //是否启用强制更新命令
        boolean isHtmlView = fileAttribute.isHtmlView();  //xlsx  转换成html
        String cacheName = fileAttribute.getCacheName();  //转换后的文件名
        String outFilePath = fileAttribute.getOutFilePath();  //转换后生成文件的路径
        if (!officePreviewType.equalsIgnoreCase("html")) {
            if (ConfigConstants.getOfficeTypeWeb().equalsIgnoreCase("web")) {
                if (suffix.equalsIgnoreCase("xlsx")) {
                    model.addAttribute("pdfUrl", KkFileUtils.htmlEscape(url)); //特殊符号处理
                    return XLSX_FILE_PREVIEW_PAGE;
                }
                if (suffix.equalsIgnoreCase("csv")) {
                    model.addAttribute("csvUrl", KkFileUtils.htmlEscape(url));
                    return CSV_FILE_PREVIEW_PAGE;
                }
            }
        }
        if (forceUpdatedCache || !fileHandlerService.listConvertedFiles().containsKey(cacheName) || !ConfigConstants.isCacheEnabled()) {
            // 下载远程文件到本地，如果文件在本地已存在不会重复下载
            ReturnResponse<String> response = DownloadUtils.downLoad(fileAttribute, fileName);
            if (response.isFailure()) {
                return otherFilePreview.notSupportedFile(model, fileAttribute, response.getMsg());
            }
            String filePath = response.getContent();
            boolean isPwdProtectedOffice = OfficeUtils.isPwdProtected(filePath);    // 判断是否加密文件
            if (isPwdProtectedOffice && !StringUtils.hasLength(filePassword)) {
                // 加密文件需要密码
                model.addAttribute("needFilePassword", true);
                return EXEL_FILE_PREVIEW_PAGE;
            } else {
                if (StringUtils.hasText(outFilePath)) {
                    try {
                        officeToPdfService.openOfficeToPDF(filePath, outFilePath, fileAttribute);
                    } catch (OfficeException e) {
                        if (isPwdProtectedOffice && !OfficeUtils.isCompatible(filePath, filePassword)) {
                            // 加密文件密码错误，提示重新输入
                            model.addAttribute("needFilePassword", true);
                            model.addAttribute("filePasswordError", true);
                            return EXEL_FILE_PREVIEW_PAGE;
                        }
                        return otherFilePreview.notSupportedFile(model, fileAttribute, "抱歉，该文件版本不兼容，文件版本错误。");
                    }
                    if (isHtmlView) {
                        // 对转换后的文件进行操作(改变编码方式)
                        fileHandlerService.doActionConvertedFile(outFilePath);
                    }
                    //是否保留OFFICE源文件
                    if (!fileAttribute.isCompressFile() && ConfigConstants.getDeleteSourceFile()) {
                        KkFileUtils.deleteFileByPath(filePath);
                    }
                    if (userToken || !isPwdProtectedOffice) {
                        // 加入缓存
                        fileHandlerService.addConvertedFile(cacheName, fileHandlerService.getRelativePath(outFilePath));
                    }
                }
            }

        }
        if (!isHtmlView && baseUrl != null && (OFFICE_PREVIEW_TYPE_IMAGE.equals(officePreviewType) || OFFICE_PREVIEW_TYPE_ALL_IMAGES.equals(officePreviewType))) {
            return getPreviewType(model, fileAttribute, officePreviewType, cacheName, outFilePath, fileHandlerService, OFFICE_PREVIEW_TYPE_IMAGE, otherFilePreview);
        }
        model.addAttribute("pdfUrl", WebUtils.encodeFileName(cacheName));  //输出转义文件名 方便url识别

        ServletRequestAttributes servletRequestAttributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = servletRequestAttributes.getRequest();
        HttpServletResponse response = servletRequestAttributes.getResponse();
        Map<String, String[]> parameterMap = request.getParameterMap();
        if (parameterMap.containsKey("re_username")) {
//            model.addAttribute("re_username", parameterMap.get("re_username")[0]);
            request.getSession().setAttribute("re_username", parameterMap.get("re_username")[0]);
        }
        if (parameterMap.containsKey("token")) {
//            model.addAttribute("token", parameterMap.get("token")[0]);
            request.getSession().setAttribute("token", parameterMap.get("token")[0]);
            String queryString = request.getQueryString();
            String redirectUrl = Arrays.stream(queryString.split("&"))
                    .filter(param -> !param.startsWith("token="))
                    .filter(param -> !param.startsWith("re_username="))
                    .collect(Collectors.joining("&"));
            try {
                response.sendRedirect(ConfigConstants.getBaseUrl() + "/onlinePreview?" + redirectUrl);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else {
            Object userToken1 = request.getSession().getAttribute("token");
            if (userToken1 != null) {
//                model.addAttribute("token", userToken1);
            }

        }


        if (parameterMap.containsKey("re_businessId")) {
            String businnessId = parameterMap.get("re_businessId")[0];
            model.addAttribute("re_businessId", businnessId);
            if (ConfigConstants.getAnnotationGetUrl().endsWith("/")) {
                model.addAttribute("re_get_url", ConfigConstants.getAnnotationGetUrl() + businnessId);
            } else {
                model.addAttribute("re_get_url", ConfigConstants.getAnnotationGetUrl() + "/" + businnessId);
            }
        }
        model.addAttribute("re_post_url", ConfigConstants.getAnnotationPostUrl());
        model.addAttribute("re_business_base_url", ConfigConstants.getBusinessBaseUrl());

        return isHtmlView ? EXEL_FILE_PREVIEW_PAGE : PDF_FILE_PREVIEW_PAGE;
    }

    static String getPreviewType(Model model, FileAttribute fileAttribute, String officePreviewType, String pdfName, String outFilePath, FileHandlerService fileHandlerService, String officePreviewTypeImage, OtherFilePreviewImpl otherFilePreview) {
        String suffix = fileAttribute.getSuffix();
        boolean isPPT = suffix.equalsIgnoreCase("ppt") || suffix.equalsIgnoreCase("pptx");
        List<String> imageUrls = null;
        try {
            imageUrls = fileHandlerService.pdf2jpg(outFilePath, outFilePath, pdfName, fileAttribute);
        } catch (Exception e) {
            Throwable[] throwableArray = ExceptionUtils.getThrowables(e);
            for (Throwable throwable : throwableArray) {
                if (throwable instanceof IOException || throwable instanceof EncryptedDocumentException) {
                    if (e.getMessage().toLowerCase().contains(OFFICE_PASSWORD_MSG)) {
                        model.addAttribute("needFilePassword", true);
                        return EXEL_FILE_PREVIEW_PAGE;
                    }
                }
            }
        }
        if (imageUrls == null || imageUrls.size() < 1) {
            return otherFilePreview.notSupportedFile(model, fileAttribute, "office转图片异常，请联系管理员");
        }
        model.addAttribute("imgUrls", imageUrls);
        model.addAttribute("currentUrl", imageUrls.get(0));
        if (officePreviewTypeImage.equals(officePreviewType)) {
            // PPT 图片模式使用专用预览页面
            return (isPPT ? PPT_FILE_PREVIEW_PAGE : OFFICE_PICTURE_FILE_PREVIEW_PAGE);
        } else {
            return PICTURE_FILE_PREVIEW_PAGE;
        }
    }

}
