package cn.lzxz1234.wxbot;

import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.log4j.Logger;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import cn.lzxz1234.wxbot.context.WXContactInfo;
import cn.lzxz1234.wxbot.context.WXHttpClientContext;
import cn.lzxz1234.wxbot.event.BatchEvent;
import cn.lzxz1234.wxbot.event.Event;
import cn.lzxz1234.wxbot.event.active.ApplyUserAddEvent;
import cn.lzxz1234.wxbot.event.active.SendMsgByUidEvent;
import cn.lzxz1234.wxbot.event.active.SendMsgEvent;
import cn.lzxz1234.wxbot.event.active.SetRemarkNameEvent;
import cn.lzxz1234.wxbot.event.system.GetContactEvent;
import cn.lzxz1234.wxbot.event.system.HandleMsgEvent;
import cn.lzxz1234.wxbot.event.system.InitEvent;
import cn.lzxz1234.wxbot.event.system.LoginEvent;
import cn.lzxz1234.wxbot.event.system.NewEvent;
import cn.lzxz1234.wxbot.event.system.ProcMsgEvent;
import cn.lzxz1234.wxbot.event.system.StatusNotifyEvent;
import cn.lzxz1234.wxbot.event.system.Wait4LoginEvent;
import cn.lzxz1234.wxbot.store.MemoryStore;
import cn.lzxz1234.wxbot.store.Store;
import cn.lzxz1234.wxbot.task.EventListener;
import cn.lzxz1234.wxbot.task.active.ApplyUserAdd;
import cn.lzxz1234.wxbot.task.active.SendMsg;
import cn.lzxz1234.wxbot.task.active.SendMsgByUid;
import cn.lzxz1234.wxbot.task.active.SetRemarkName;
import cn.lzxz1234.wxbot.task.passive.Batch;
import cn.lzxz1234.wxbot.task.passive.GetContact;
import cn.lzxz1234.wxbot.task.passive.HandleMsg;
import cn.lzxz1234.wxbot.task.passive.Init;
import cn.lzxz1234.wxbot.task.passive.Login;
import cn.lzxz1234.wxbot.task.passive.New;
import cn.lzxz1234.wxbot.task.passive.ProcMsg;
import cn.lzxz1234.wxbot.task.passive.StatusNotify;
import cn.lzxz1234.wxbot.task.passive.Wait4Login;
import cn.lzxz1234.wxbot.utils.Exec;
import cn.lzxz1234.wxbot.utils.Lang;

public class WXUtils {

    protected static final Logger log = Logger.getLogger(WXUtils.class);
    protected static final ConcurrentHashMap<Class<?>, Class<?>[]> map = new ConcurrentHashMap<Class<?>, Class<?>[]>();
    protected static Store store = new MemoryStore();
    
    static {
        
        registEventListener(ApplyUserAddEvent.class, ApplyUserAdd.class);
        registEventListener(GetContactEvent.class, GetContact.class);
        registEventListener(HandleMsgEvent.class, HandleMsg.class);
        registEventListener(InitEvent.class, Init.class);
        registEventListener(LoginEvent.class, Login.class);
        registEventListener(NewEvent.class, New.class);
        registEventListener(ProcMsgEvent.class, ProcMsg.class);
        registEventListener(SendMsgByUidEvent.class, SendMsgByUid.class);
        registEventListener(SendMsgEvent.class, SendMsg.class);
        registEventListener(SetRemarkNameEvent.class, SetRemarkName.class);
        registEventListener(StatusNotifyEvent.class, StatusNotify.class);
        registEventListener(Wait4LoginEvent.class, Wait4Login.class);
        registEventListener(BatchEvent.class, Batch.class);
        
        System.setProperty ("jsse.enableSNIExtension", "false");
    }
    
    /**
     * 全局注册事件监听，支持父类监听
     * @param event
     * @param listener
     */
    public static synchronized <T extends Event> void registEventListener(Class<T> event, Class<? extends EventListener<T>> listener) {
        
        if(!map.contains(event)) map.put(event, new Class<?>[0]);
        List<Class<?>> list = new ArrayList<Class<?>>(Arrays.asList(map.get(event)));
        list.add(listener);
        map.put(event, list.toArray(new Class<?>[0]));
        log.info(String.format("监听注册 %s ==> %s", event, listener));
    }

    /**
     * 开始登录会话，生成 UUID，并将传入的参数作为其它信息保存到 context 中
     * @param otherInfo
     * @return
     * @throws Exception
     */
    public static String genUUID(Map<String, Object> otherInfo) throws Exception {
        
        // appid=wx782c26e4c19acffb 为 Web微信
        // appid=wxeb7ec651dd0aefa9 为 微信网页版
        URI uri = new URIBuilder("https://login.weixin.qq.com/jslogin")
                .addParameter("appid", "wx782c26e4c19acffb")
                .addParameter("fun", "new")
                .addParameter("lang", "zh_CN")
                .addParameter("_", String.valueOf(System.currentTimeMillis()))
                .build();
        HttpGet get = new HttpGet(uri);
        CloseableHttpClient client = HttpClients.createDefault();
        CloseableHttpResponse resp = null;
        try {
            resp = client.execute(get);
            String data = IOUtils.toString(resp.getEntity().getContent(), "UTF-8");
            Pattern pattern = Pattern.compile("window.QRLogin.code = (\\d+); window.QRLogin.uuid = \"(\\S+?)\"");
            Matcher matcher = pattern.matcher(data);
            if(matcher.find()) {
                String code = matcher.group(1);
                String uuid = matcher.group(2);
                if("200".equals(code)) {
                    new Thread(new EventHandler<Event>(new NewEvent(uuid, otherInfo)), uuid).start();
                    return uuid;
                }
            }
        } finally {
            resp.close();
            client.close();
        }
        return null;
    }
    
    /**
     * 查询会话
     * @param uuid
     * @return
     * @throws Exception
     */
    public static WXHttpClientContext getContext(String uuid) throws Exception {
        
        WXHttpClientContext result = store.getContext(uuid);
        if(result == null) {
            result = new WXHttpClientContext();
            result.setUuid(uuid);
            store.saveContext(result);
        }
        return result;
    }
    
    /**
     * 保存会话
     * @param context
     */
    public static void saveContext(WXHttpClientContext context) {
        
        store.saveContext(context);
    }
    
    /**
     * 查询联系人信息
     * @param uuid
     * @return
     */
    public static WXContactInfo getContact(String uuid) {
        
        return store.getContact(uuid);
    }
    
    /**
     * 保存联系人信息
     * @param contact
     */
    public static void saveContact(WXContactInfo contact) {
        
        store.saveContact(contact);
    }
    
    /**
     * 生成二维码
     * @param uuid
     * @param stream
     * @throws Exception
     */
    public static void genQrCode(String uuid, OutputStream stream) throws Exception {
        
        String content = "https://login.weixin.qq.com/l/" + uuid;
        BitMatrix bitMatrix = new QRCodeWriter().encode(content,  
                BarcodeFormat.QR_CODE, 200, 200);
        MatrixToImageWriter.writeToStream(bitMatrix, "png", stream);
    }
    
    /**
     * 提交任务
     * @param task
     */
    public static void submit(Event task) {
        
        try {
            if(task != null) 
                Exec.submit(task.getUuid(), new EventHandler<Event>(task));
        } catch (Exception e) {
            log.error("入队失败", e);
            submit(task);
        }
    }
    
    public static class EventHandler<T extends Event> implements Runnable {
        
        private T e;
        public EventHandler(T e) {
            this.e = e;
        }
        @Override
        @SuppressWarnings("unchecked")
        public void run() {
            
            while(e != null) {
                Class<?> cur = e.getClass();
                do {
                    Class<?>[] classes = map.get(cur);
                    if(classes != null)
                        for(Class<?> tmp : classes) {
                            try {
                                EventListener<T> listener = Lang.newInstance(tmp);
                                e = (T) listener.handleEvent(e);
                            } catch (Exception e) {
                                log.error("处理失败", e);
                            }
                        }
                    cur = cur.getSuperclass();
                } while(!cur.equals(Object.class));
            }
        }
    }
}