package com.company.platform;

import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.json.JsonMapper;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** 随机凭据、密码散列和非秘密载荷摘要；禁止把密码传给幂等摘要。 */
public final class Crypto {
    /** 不依赖可逆加密保存员工密码，每次编码使用独立盐。 */
    private static final PasswordEncoder PASSWORDS=Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    /** 操作系统安全随机源，生成足够熵的会话与临时密码。 */
    private static final SecureRandom RANDOM=new SecureRandom();
    /** 共享不可变 JSON 编解码器，不记录原始请求。 */
    public static final JsonMapper JSON=JsonMapper.builder().build();
    private Crypto() { }
    /** 生成 256 位随机秘密，仅一次投递或经 HttpOnly Cookie 交付。 */
    public static String token() { byte[] bytes=new byte[32]; RANDOM.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    /** 计算带盐自适应密码散列；非空规则由接口校验。 */
    public static String password(String raw) { return PASSWORDS.encode(raw); }
    /** 验证密码，不对调用者返回内部散列。 */
    public static boolean matches(String raw, Object encoded) { return raw!=null && raw.length()<=4096 && encoded instanceof String hash && PASSWORDS.matches(raw,hash); }
    /** 高熵令牌、非秘密数据的 SHA-256；不能用于普通密码存储。 */
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (GeneralSecurityException e) { throw new IllegalStateException("摘要算法不可用"); }
    }
    /** 凭据比较不提前返回首个不匹配字节。 */
    public static boolean equal(String left, String right) { return left!=null && right!=null && MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8),right.getBytes(StandardCharsets.UTF_8)); }
    /** 把公开标识变成不可枚举的限速桶键，不把原始来源写入计数器。 */
    public static String hmac(String secret, String value) {
        try { Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256")); return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8))); }
        catch (GeneralSecurityException e) { throw new IllegalStateException("认证摘要算法不可用"); }
    }
    /** 递归排序对象字段；数组次序仍有业务含义，由各领域先规范化。 */
    public static Object canonical(Object value) {
        if (value instanceof Map<?,?> map) { Map<String,Object> sorted=new TreeMap<>(); map.forEach((k,v)->sorted.put(k.toString(),canonical(v))); return sorted; }
        if (value instanceof List<?> list) return list.stream().map(Crypto::canonical).toList();
        return value;
    }
    /** 输出规范 JSON，供非秘密请求与操作对象清单摘要使用。 */
    public static String json(Object value) { return JSON.writeValueAsString(canonical(value)); }
}
