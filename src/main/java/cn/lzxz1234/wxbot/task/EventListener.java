package cn.lzxz1234.wxbot.task;

import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang.StringUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.log4j.Logger;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import cn.lzxz1234.wxbot.WXUtils;
import cn.lzxz1234.wxbot.context.WXHttpClientContext;
import cn.lzxz1234.wxbot.event.Event;
import cn.lzxz1234.wxbot.vo.Name;

public abstract class EventListener<T extends Event> {

    protected Logger log = Logger.getLogger(this.getClass());

    protected abstract Event handleEnvent(T e, WXHttpClientContext context) throws Exception;
    
    public final Event handleEvent(T e) throws Exception {
        
        WXHttpClientContext context = null;
        try {
            context = WXUtils.getStore().getContext(e.getUuid());
            if(context == null) {
                context = new WXHttpClientContext();
                context.setUuid(e.getUuid());
                context.setStatus("new");
                WXUtils.getStore().saveContext(context);
            }
            return this.handleEnvent(e, context);
        } catch(Exception ex) {
            log.error(e.getUuid() + " 执行失败 " + JSON.toJSONString(e), ex);
        } finally {
            context.setUpdateTime(new Date());
            context.close();
            WXUtils.getStore().saveContext(context);
        }
        return null;
    }
    
    public CloseableHttpResponse execute(HttpUriRequest request, WXHttpClientContext cookie) 
            throws Exception {
        
        return cookie.execute(request);
    }
    
    public Name getContactName(WXHttpClientContext context, String uid) {
        
        JSONObject info = this.getContactInfo(context, uid);
        if(info == null) return null;
        info = info.getJSONObject("info");
        Name name = new Name();
        name.setRemarkName(info.getString("RemarkName"));
        name.setNickName(info.getString("NickName"));
        name.setDisplayName(info.getString("DisplayName"));
        return name;
    }
    
    public Name getGroupMemberName(WXHttpClientContext context, String gid, String uid) {
        
        JSONArray group = WXUtils.getContact(context.getUuid()).getGroupMembers().getJSONArray(gid);
        if(group == null) return null;
        for(int i = 0; i < group.size(); i ++) {
            JSONObject member = group.getJSONObject(i);
            if(member.getString("UserName").equals(uid)) {
                Name name = new Name();
                name.setRemarkName(member.getString("RemarkName"));
                name.setNickName(member.getString("NickName"));
                name.setDisplayName(member.getString("DisplayName"));
                return name;
            }
        }
        return null;
    }
    
    public String searchContent(String key, String content, String fmat) {
        
        if("attr".equals(fmat)) {
            Matcher matcher = Pattern.compile(key + "\\s?=\\s?\"([^\"<]+)\"").matcher(content);
            if(matcher.find()) return matcher.group(1);
        } else if("xml".equals(fmat)) {
            Matcher matcher = Pattern.compile("<" + key + ">([^<]+)</\" + key + \">").matcher(content);
            if(matcher.find()) return matcher.group(1);
        }
        return "unknown";
    }
    
    public JSONObject getContactInfo(WXHttpClientContext context, String uid) {
        
        return WXUtils.getContact(context.getUuid()).getNormalMember().getJSONObject(uid);
    }
    
    public boolean isContact(WXHttpClientContext context, String uid) {
        
        for(JSONObject each : WXUtils.getContact(context.getUuid()).getContactList()) 
            if(uid.equals(each.getString("UserName")))
                return true;
        return false;
    }
    
    public boolean isPublic(WXHttpClientContext context, String uid) {
        
        for(JSONObject each : WXUtils.getContact(context.getUuid()).getPublicList()) 
            if(uid.equals(each.getString("UserName")))
                return true;
        return false;
    }
    
    public boolean isSpecial(WXHttpClientContext context, String uid) {
        
        for(JSONObject each : WXUtils.getContact(context.getUuid()).getSpecialList()) 
            if(uid.equals(each.getString("UserName")))
                return true;
        return false;
    }
    
    public String getContactPreferName(Name name) {
        
        if(name == null) return null;
        if(StringUtils.isNotEmpty(name.getRemarkName())) 
            return name.getRemarkName();
        if(StringUtils.isNotEmpty(name.getNickName())) 
            return name.getNickName();
        if(StringUtils.isNotEmpty(name.getDisplayName())) 
            return name.getDisplayName();
        return null;
    }
    
    public String getGroupMemberPreferName(Name name) {
        
        if(name == null) return null;
        if(StringUtils.isNotEmpty(name.getRemarkName())) 
            return name.getRemarkName();
        if(StringUtils.isNotEmpty(name.getDisplayName())) 
            return name.getDisplayName();
        if(StringUtils.isNotEmpty(name.getNickName())) 
            return name.getNickName();
        return null;
    }
    
}
