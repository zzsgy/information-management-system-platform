package com.company.ims.identity;

import com.company.ims.shared.BusinessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;

/** 统一密码长度与加盐哈希策略，不依赖外部密钥或固定初始口令。 */
final class IdentityPasswords {
    /** 使用Spring提供的PBKDF2参数，避免明文和可逆密码保存。 */
    private static final PasswordEncoder ENCODER = Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    /** 工具类不允许实例化。 */
    private IdentityPasswords() { }

    /** 验证12至128字符长度后计算随机盐哈希，返回值可安全持久化。 */
    static String encode(String password) {
        if (password == null || password.length() < 12 || password.length() > 128) {
            throw new BusinessException(400, "密码长度须为12至128个字符");
        }
        return ENCODER.encode(password);
    }

    /** 对输入密码进行恒定策略校验；空值和超长输入直接拒绝。 */
    static boolean matches(String password, String hash) {
        return password != null && password.length() <= 128 && hash != null && ENCODER.matches(password, hash);
    }
}
