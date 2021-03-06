package cn.lzxz1234.wxbot.task.passive;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.StringEntity;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import cn.lzxz1234.wxbot.WXUtils;
import cn.lzxz1234.wxbot.context.WXHttpClientContext;
import cn.lzxz1234.wxbot.event.Event;
import cn.lzxz1234.wxbot.event.system.GetContactEvent;
import cn.lzxz1234.wxbot.event.system.HandleMsgEvent;
import cn.lzxz1234.wxbot.event.system.ProcMsgEvent;
import cn.lzxz1234.wxbot.task.EventListener;
import cn.lzxz1234.wxbot.utils.Lang;
import cn.lzxz1234.wxbot.vo.SyncKey;

public class ProcMsg extends EventListener<ProcMsgEvent> {

    @Override
    public Event handleEnvent(ProcMsgEvent e, WXHttpClientContext context)
            throws Exception {
        
        if(context.getStatus().equals("loginout")) return null;
        
        long checkTime = System.currentTimeMillis();
        CloseableHttpResponse resp = null;
        try {
            if(context.getStatus().equals("wait4loginout")) return null;
            
            if(StringUtils.isEmpty(context.getSyncHost()))
                this.testSyncCheck(context);
            // 获取不到同步服务器时出退出
            if(StringUtils.isEmpty(context.getSyncHost())) return null;
            
            context.setStatus("success");
            String uuid = e.getUuid();
            URI uri = new URIBuilder("https://" + context.getSyncHost() + "/cgi-bin/mmwebwx-bin/synccheck")
                    .addParameter("r", String.valueOf(System.currentTimeMillis() / 1000))
                    .addParameter("sid", context.getBaseRequest().getSid())
                    .addParameter("uin", context.getBaseRequest().getUin())
                    .addParameter("skey", context.getBaseRequest().getSkey())
                    .addParameter("deviceid", context.getBaseRequest().getDeviceId())
                    .addParameter("synckey", context.getSyncKeyString())
                    .addParameter("_", String.valueOf(System.currentTimeMillis() / 1000))
                    .build();
            HttpGet get = new HttpGet(uri);
            get.setConfig(RequestConfig.custom().setSocketTimeout(30000).build());
            resp = this.execute(get, context);
            String data = IOUtils.toString(resp.getEntity().getContent(), "UTF-8");
            Matcher matcher = Pattern.compile("window.synccheck=\\{retcode:\"(\\d+)\",selector:\"(\\d+)\"\\}").matcher(data);
            if(matcher.find()) {
                String retcode = matcher.group(1);
                String selector = matcher.group(2);
                
                if("1100".equals(retcode)) { // 从微信客户端上登出
                    context.setStatus("loginout");
                    log.info(uuid + " 退出 " + 1100);
                } else if("1101".equals(retcode)){ // 从其它设备上登录了网页微信
                    context.setStatus("loginout");
                    log.info(uuid + " 退出 " + 1101);
                } else if("1102".equals(retcode)) { // 不知道啥问题，反正就是退出登录了
                    context.setStatus("loginout");
                    log.info(uuid + " 退出 " + 1102);
                } else if("0".equals(retcode)) {
                    if(!"0".equals(selector)) {
                        JSONObject dict = this.sync(context);
                        if(dict != null) {
                            if("2".equals(selector)) { // 有新消息 
                                WXUtils.submit(new HandleMsgEvent(uuid, dict));
                            } else if("3".equals(selector)) { // 未知
                                WXUtils.submit(new HandleMsgEvent(uuid, dict));
                            } else if("4".equals(selector)) { // 通讯录更新
                                WXUtils.submit(new GetContactEvent(uuid));
                            } else if("6".equals(selector)) { // 可能是红包
                                WXUtils.submit(new HandleMsgEvent(uuid, dict));
                            } else if("7".equals(selector)) { // 手机上操作了微信 
                                WXUtils.submit(new HandleMsgEvent(uuid, dict));
                            } else {
                                log.debug(e.getUuid() + " sync_check: " + retcode + "，" + selector);
                                WXUtils.submit(new HandleMsgEvent(uuid, dict));
                            }
                        }
                    }
                } else {
                    log.debug(uuid + " sync_check: " + retcode + "，" + selector);
                }
            }
        } catch(Exception ex) {
            log.error("执行异常", ex);
        } finally {
            if(resp != null) resp.close();
        }
        checkTime = System.currentTimeMillis() - checkTime;
        if(checkTime < 800) Lang.sleep(1000 - checkTime);
        return new ProcMsgEvent(e.getUuid());
    }

    private void testSyncCheck(WXHttpClientContext context) throws Exception {
        
        String uuid = context.getUuid();
        Pattern pattern = Pattern.compile("window.synccheck=\\{retcode:\"(\\d+)\",selector:\"(\\d+)\"\\}");
        for(String host : new String[] {"webpush.", "webpush2."}) {
            URI uri = new URIBuilder("https://" + host + context.getBaseHost() + "/cgi-bin/mmwebwx-bin/synccheck")
                    .addParameter("r", String.valueOf(System.currentTimeMillis() / 1000))
                    .addParameter("sid", context.getBaseRequest().getSid())
                    .addParameter("uin", context.getBaseRequest().getUin())
                    .addParameter("skey", context.getBaseRequest().getSkey())
                    .addParameter("deviceid", context.getBaseRequest().getDeviceId())
                    .addParameter("synckey", context.getSyncKeyString())
                    .addParameter("_", String.valueOf(System.currentTimeMillis() / 1000))
                    .build();
            HttpGet get = new HttpGet(uri);
            CloseableHttpResponse resp = null;
            try {
                resp = this.execute(get, context);
                String data = IOUtils.toString(resp.getEntity().getContent(), "UTF-8");
                Matcher matcher = pattern.matcher(data);
                if(matcher.find() && "0".equals(matcher.group(1))) {
                    context.setSyncHost(host + context.getBaseHost());
                    log.debug(uuid + " 加载同步服务器：" + context.getSyncHost());
                    return;
                } else {
                    log.warn("测试服务器返回:" + data);
                }
            } catch (Exception e) {
                log.warn("加载服务器失败:" + e.getMessage());
            } finally {
                if(resp != null) resp.close();
            }
        }
    }

    private JSONObject sync(WXHttpClientContext context) throws Exception {
        
        URI uri = new URIBuilder(context.getBaseUri() + "/webwxsync")
                .addParameter("sid", context.getBaseRequest().getSid())
                .addParameter("skey", context.getBaseRequest().getSkey())
                .addParameter("lang", "en_US")
                .addParameter("pass_ticket", context.getPassTicket())
                .build();
        HttpPost post = new HttpPost(uri);
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("BaseRequest", context.getBaseRequest());
        params.put("SyncKey", context.getSyncKey());
        params.put("rr", String.valueOf(~(System.currentTimeMillis() / 1000)));
        post.setEntity(new StringEntity(JSON.toJSONString(params)));

        CloseableHttpResponse resp = this.execute(post, context);
        try {
            String data = IOUtils.toString(resp.getEntity().getContent(), "UTF-8");
            JSONObject dict = JSON.parseObject(data);
            if(dict.getJSONObject("BaseResponse").getInteger("Ret") == 0) {
                context.setSyncKey(dict.getObject("SyncCheckKey", SyncKey.class));
                return dict;
            }
        } finally {
            resp.close();
        }
        return null;
    }

}
