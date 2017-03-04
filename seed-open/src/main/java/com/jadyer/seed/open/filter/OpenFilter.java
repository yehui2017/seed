package com.jadyer.seed.open.filter;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.jadyer.seed.comm.constant.CommonResult;
import com.jadyer.seed.comm.exception.SeedException;
import com.jadyer.seed.comm.util.IPUtil;
import com.jadyer.seed.comm.util.LogUtil;
import com.jadyer.seed.open.constant.OpenCodeEnum;
import com.jadyer.seed.open.constant.OpenConstant;
import com.jadyer.seed.open.model.ReqData;
import com.jadyer.seed.open.model.RespData;
import com.jadyer.seed.open.util.CodecUtil;
import com.jadyer.seed.open.util.OpenUtil;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 开放平台Filter
 * <p>负责解析请求参数以及加解密等操作</p>
 * Created by 玄玉<https://jadyer.github.io/> on 2016/5/8 19:34.
 */
public class OpenFilter extends OncePerRequestFilter {
    private String filterURL;
    private Map<String, String> appsecretMap = new HashMap<>();
    private Map<String, List<String>> apiGrantMap = new HashMap<>();

    /**
     * @param _filterURL    指定该Filter只拦截哪种请求URL,null表示拦截所有
     * @param _apiGrantMap  为各个appid初始化API授权情况
     * @param _appsecretMap 为各个appid初始化appsecret
     */
    public OpenFilter(String _filterURL, Map<String, List<String>> _apiGrantMap, Map<String, String> _appsecretMap){
        this.filterURL = _filterURL;
        this.apiGrantMap = _apiGrantMap;
        this.appsecretMap = _appsecretMap;
    }


    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        return StringUtils.isNotBlank(filterURL) && !request.getServletPath().startsWith(filterURL);
    }


    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        ReqData reqData;
        String reqDataStr;
        String method = request.getParameter("method");
        if(StringUtils.isNotBlank(method) && (method.endsWith("file.upload")||method.endsWith("h5"))){
            reqDataStr = OpenUtil.buildStringFromMapWithStringArray(request.getParameterMap());
            LogUtil.getAppLogger().debug("收到客户端IP=[{}]的请求报文为-->{}", IPUtil.getClientIP(request), reqDataStr);
            reqData = OpenUtil.requestToBean(request, ReqData.class);
        }else{
            reqDataStr = IOUtils.toString(request.getInputStream(), OpenConstant.CHARSET_UTF8);
            LogUtil.getAppLogger().debug("收到客户端IP=[{}]的请求报文为-->[{}]", IPUtil.getClientIP(request), reqDataStr);
            reqData = JSON.parseObject(reqDataStr, ReqData.class);
            method = reqData.getMethod();
        }
        try{
            //验证请求方法名非空
            if(StringUtils.isBlank(reqData.getMethod())){
                throw new SeedException(OpenCodeEnum.UNKNOWN_METHOD.getCode(), String.format("%s-->[%s]", OpenCodeEnum.UNKNOWN_METHOD.getMsg(), reqData.getMethod()));
            }
            //验证时间戳
            this.verifyTimestamp(reqData.getTimestamp(), 600000);
            //识别合作方
            String appsecret = appsecretMap.get(reqData.getAppid());
            LogUtil.getAppLogger().debug("通过appid=[{}]读取到合作方密钥{}", reqData.getAppid(), appsecret);
            if(appsecretMap.isEmpty() || StringUtils.isBlank(appsecret)){
                throw new SeedException(OpenCodeEnum.UNKNOWN_APPID.getCode(), OpenCodeEnum.UNKNOWN_APPID.getMsg());
            }
            //获取协议版本
            if(!OpenConstant.VERSION_21.equals(reqData.getVersion()) && !OpenConstant.VERSION_22.equals(reqData.getVersion())){
                throw new SeedException(OpenCodeEnum.UNKNOWN_VERSION.getCode(), OpenCodeEnum.UNKNOWN_VERSION.getMsg());
            }
            //验证接口是否已授权
            this.verifyGrant(reqData.getAppid(), method);
            //验签
            //if(Constants.VERSION_20.equals(reqData.getVersion())){
            //	this.verifySign(request.getParameterMap(), apiApplication.getAppSecret());
            //	filterChain.doFilter(request, response);
            //}
            //解密并处理
            RequestParameterWrapper requestWrapper = new RequestParameterWrapper(request);
            requestWrapper.addAllParameters(this.decrypt(reqData, appsecret));
            ResponseContentWrapper responseWrapper = new ResponseContentWrapper(response);
            filterChain.doFilter(requestWrapper, responseWrapper);
            String content = responseWrapper.getContent();
            LogUtil.getAppLogger().info("返回客户端IP=["+IPUtil.getClientIP(request)+"]的应答明文为-->[{}]", content);
            if(method.endsWith("h5") || method.endsWith("agree") || method.endsWith("download")){
                response.getOutputStream().write(content.getBytes(OpenConstant.CHARSET_UTF8));
            }else{
                RespData respData = JSON.parseObject(content, RespData.class);
                if(OpenCodeEnum.SUCCESS.getCode() == Integer.parseInt(respData.getCode())){
                    if(OpenConstant.VERSION_21.equals(reqData.getVersion())){
                        respData.setData(CodecUtil.buildAESEncrypt(respData.getData(), appsecret));
                    }else{
                        Map<String, String> dataMap = JSON.parseObject(appsecret, new TypeReference<Map<String, String>>(){});
                        respData.setSign(CodecUtil.buildRSASignByPrivateKey(respData.getData(), dataMap.get("openPrivateKey")));
                        respData.setData(CodecUtil.buildRSAEncryptByPublicKey(respData.getData(), dataMap.get("publicKey")));
                    }
                }
                String respDataJson = JSON.toJSONString(respData);
                LogUtil.getAppLogger().debug("返回客户端IP=["+IPUtil.getClientIP(request)+"]的应答密文为-->[{}]", respDataJson);
                response.getOutputStream().write(respDataJson.getBytes(OpenConstant.CHARSET_UTF8));
            }
            //异步记录接口访问日志
            this.apilog(reqData.getAppid(), method, IPUtil.getClientIP(request), reqDataStr, content);
        }catch(SeedException e){
            response.setHeader("Content-Type", "application/json;charset=" + OpenConstant.CHARSET_UTF8);
            response.getOutputStream().write(JSON.toJSONString(new CommonResult(e.getCode(), e.getMessage()), true).getBytes(OpenConstant.CHARSET_UTF8));
        }
    }


    /**
     * 验证时间戳
     * @param milliseconds 有效期毫秒数,10分钟即为600000
     */
    private void verifyTimestamp(String timestamp, long milliseconds){
        if(StringUtils.isBlank(timestamp)){
            throw new SeedException(OpenCodeEnum.SYSTEM_BUSY.getCode(), "timestamp is blank");
        }
        try {
            long reqTime = DateUtils.parseDate(timestamp, "yyyy-MM-dd HH:mm:ss").getTime();
            if(Math.abs(System.currentTimeMillis()-reqTime) >= milliseconds){
                throw new SeedException(OpenCodeEnum.TIMESTAMP_ERROR.getCode(), OpenCodeEnum.TIMESTAMP_ERROR.getMsg());
            }
        } catch (ParseException e) {
            throw new SeedException(OpenCodeEnum.SYSTEM_BUSY.getCode(), "timestamp is invalid");
        }
    }


    /**
     * 验证接口是否授权
     */
    private void verifyGrant(String appid, String method){
        boolean isGrant = false;
        for (Map.Entry<String, List<String>> entry : apiGrantMap.entrySet()) {
            if(appid.equals(entry.getKey())){
                if(entry.getValue().contains(method)){
                    isGrant = true;
                    break;
                }
            }
        }
        if(!isGrant){
            throw new SeedException(OpenCodeEnum.UNGRANT_API.getCode(), "未授权的接口-->["+method+"]");
        }
    }


    /**
     * 验签
     */
    @SuppressWarnings("unused")
    private void verifySign(Map<String, String[]> paramMap, String appsecret){
        String signType = paramMap.get("signType")[0];
        if(!OpenConstant.SIGN_TYPE_md5.equals(signType) && !OpenConstant.SIGN_TYPE_hmac.equals(signType)){
            throw new SeedException(OpenCodeEnum.UNKNOWN_SIGN.getCode(), OpenCodeEnum.UNKNOWN_SIGN.getMsg());
        }
        StringBuilder sb = new StringBuilder();
        List<String> keys = new ArrayList<>(paramMap.keySet());
        Collections.sort(keys);
        for(String key : keys){
            String[] value = paramMap.get(key);
            if(key.equalsIgnoreCase("sign")){
                continue;
            }
            sb.append(key).append(value[0]);
        }
        boolean verfiyResult;
        if(OpenConstant.SIGN_TYPE_md5.equals(signType)){
            String data = sb.append(appsecret).toString();
            String sign = DigestUtils.md5Hex(data);
            LogUtil.getAppLogger().debug("请求参数签名原文-->[{}]", data);
            LogUtil.getAppLogger().debug("请求参数签名得到-->[{}]", sign);
            verfiyResult = sign.equals(paramMap.get("sign")[0]);
        }else{
            String sign = CodecUtil.buildHmacSign(sb.toString(), appsecret, "HmacMD5");
            LogUtil.getAppLogger().debug("请求参数签名原文-->[{}]", sb.toString());
            LogUtil.getAppLogger().debug("请求参数签名得到-->[{}]", sign);
            verfiyResult = sign.equals(paramMap.get("sign")[0]);
        }
        if(!verfiyResult){
            throw new SeedException(OpenCodeEnum.SIGN_ERROR.getCode(), OpenCodeEnum.SIGN_ERROR.getMsg());
        }
    }


    /**
     * 解密
     * <ul>
     *     <li>2.1--AES--直接解密</li>
     *     <li>2.2--RSA--公钥加密，私钥解密--私钥签名，公钥验签</li>
     * </ul>
     */
    private Map<String, Object> decrypt(ReqData reqData, String appsecret){
        if(StringUtils.isBlank(reqData.getData())){
            throw new SeedException(OpenCodeEnum.SYSTEM_BUSY.getCode(), "data is blank");
        }
        String dataPlain;
        if(OpenConstant.VERSION_21.equals(reqData.getVersion())){
            dataPlain = CodecUtil.buildAESDecrypt(reqData.getData(), appsecret);
        }else{
            //appsecret={"publicKey":"合作方公钥","openPublicKey":"我方公钥","openPrivateKey":"我方私钥"}
            Map<String, String> dataMap = JSON.parseObject(appsecret, new TypeReference<Map<String, String>>(){});
            dataPlain = CodecUtil.buildRSADecryptByPrivateKey(reqData.getData(), dataMap.get("openPrivateKey"));
            if(!CodecUtil.buildRSAverifyByPublicKey(dataPlain, dataMap.get("publicKey"), reqData.getSign())){
                throw new SeedException(OpenCodeEnum.SIGN_ERROR.getCode(), OpenCodeEnum.SIGN_ERROR.getMsg());
            }
        }
        LogUtil.getAppLogger().info("请求参数解密得到dataPlain=[{}]", dataPlain);
        reqData.setData(dataPlain);
        Map<String, Object> allParams = new HashMap<>();
        for(Field field : reqData.getClass().getDeclaredFields()){
            if(!field.getName().equals("serialVersionUID")){
                String methodName = "get" + StringUtils.capitalize(field.getName());
                Object fieldValue;
                try {
                    fieldValue = reqData.getClass().getDeclaredMethod(methodName).invoke(reqData);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                allParams.put(field.getName(), fieldValue);
            }
        }
        return allParams;
    }


    /**
     * 异步记录接口访问日志
     * @create Jan 15, 2016 10:02:49 PM
     * @author 玄玉<https://jadyer.github.io/>
     */
    private void apilog(final String appid, final String method, final String reqIp, final String reqData, final String respData){
        ExecutorService threadPool = Executors.newSingleThreadExecutor();
        threadPool.execute(new Runnable(){
            @Override
            public void run() {
                try {
                    LogUtil.getAppLogger().info("记录日志到Database或MongoDB等等");
                } catch (Exception e) {
                    LogUtil.getAppLogger().info("记录appid=["+appid+"]的接口["+method+"]访问日志时发生异常, 堆栈轨迹如下", e);
                }
            }
        });
        threadPool.shutdown();
    }


    /**
     * 可手工设置HttpServletRequest入参的Wrapper
     * ---------------------------------------------------------------------------------------
     * 由于HttpServletRequest.getParameterMap()得到的Map是immutable的,不可更改的
     * 而且HttpServletRequest.setAttribute()方法也是不能修改请求参数的,故扩展此类
     * ---------------------------------------------------------------------------------------
     * RequestParameterWrapper requestWrapper = new RequestParameterWrapper(request);
     * requestWrapper.addAllParameters(Map<String, Object> allParams);
     * filterChain.doFilter(requestWrapper, response);
     * ---------------------------------------------------------------------------------------
     * @create Dec 19, 2015 9:06:04 PM
     * @author 玄玉<https://jadyer.github.io/>
     */
    private class RequestParameterWrapper extends HttpServletRequestWrapper {
        private Map<String, String[]> paramMap = new HashMap<>();
        RequestParameterWrapper(HttpServletRequest request) {
            super(request);
            this.paramMap.putAll(request.getParameterMap());
        }
        @Override
        public String getParameter(String name) {
            String[] values = this.paramMap.get(name);
            if(null==values || values.length==0){
                return null;
            }
            return values[0];
        }
        @Override
        public Map<String, String[]> getParameterMap() {
            return this.paramMap;
        }
        @Override
        public Enumeration<String> getParameterNames() {
            return new Vector<>(this.paramMap.keySet()).elements();
        }
        @Override
        public String[] getParameterValues(String name) {
            return this.paramMap.get(name);
        }
        void addParameter(String name, Object value){
            if(null != value){
                if(value instanceof String[]){
                    this.paramMap.put(name, (String[])value);
                }else if(value instanceof String){
                    this.paramMap.put(name, new String[]{(String)value});
                }else{
                    this.paramMap.put(name, new String[]{String.valueOf(value)});
                }
            }
        }
        void addAllParameters(Map<String, Object> allParams){
            for(Map.Entry<String,Object> entry : allParams.entrySet()){
                this.addParameter(entry.getKey(), entry.getValue());
            }
        }
    }


    /**
     * 可手工设置HttpServletResponse出参的Wrapper
     * ---------------------------------------------------------------------------------------
     * ResponseContentWrapper responseWrapper = new ResponseContentWrapper(response);
     * filterChain.doFilter(request, responseWrapper);
     * String content = responseWrapper.getContent();
     * response.getOutputStream().write(content.getBytes("UTF-8"));
     * return;
     * ---------------------------------------------------------------------------------------
     * response.setHeader("Content-Type", "application/json; charset=UTF-8");
     * //response.getWriter().write("abcdefg");
     * response.getOutputStream().write(("{\"code\":\"102\", \"msg\":\"重复请求\"}").getBytes("UTF-8"));
     * return;
     * ---------------------------------------------------------------------------------------
     * @create Dec 19, 2015 9:07:09 PM
     * @author 玄玉<https://jadyer.github.io/>
     */
    private class ResponseContentWrapper extends HttpServletResponseWrapper {
        private ResponsePrintWriter writer;
        private OutputStreamWrapper outputWrapper;
        private ByteArrayOutputStream output;
        ResponseContentWrapper(HttpServletResponse httpServletResponse) {
            super(httpServletResponse);
            output = new ByteArrayOutputStream();
            outputWrapper = new OutputStreamWrapper(output);
            writer = new ResponsePrintWriter(output);
        }
        public void finalize() throws Throwable {
            super.finalize();
            output.close();
            writer.close();
        }
        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            return outputWrapper;
        }
        public String getContent() {
            try {
                writer.flush();
                return writer.getByteArrayOutputStream().toString(OpenConstant.CHARSET_UTF8);
            } catch (UnsupportedEncodingException e) {
                return "UnsupportedEncoding";
            }
        }
        public void close() throws IOException {
            writer.close();
        }
        @Override
        public PrintWriter getWriter() throws IOException {
            return writer;
        }
        private class ResponsePrintWriter extends PrintWriter {
            ByteArrayOutputStream output;
            ResponsePrintWriter(ByteArrayOutputStream output) {
                super(output);
                this.output = output;
            }
            ByteArrayOutputStream getByteArrayOutputStream() {
                return output;
            }
        }
        private class OutputStreamWrapper extends ServletOutputStream {
            ByteArrayOutputStream output;
            OutputStreamWrapper(ByteArrayOutputStream output) {
                this.output = output;
            }
            @Override
            public boolean isReady() {
                return true;
            }
            @Override
            public void setWriteListener(WriteListener listener) {
                throw new UnsupportedOperationException("UnsupportedMethod setWriteListener.");
            }
            @Override
            public void write(int b) throws IOException {
                output.write(b);
            }
        }
    }
}