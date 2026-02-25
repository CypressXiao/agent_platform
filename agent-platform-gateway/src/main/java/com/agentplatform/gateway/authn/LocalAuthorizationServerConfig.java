package com.agentplatform.gateway.authn;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.SecurityFilterChain;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

/**
 * 本地自签发 token 的授权服务器配置。
 * 通过 mcp.gateway.auth-mode=local 开关启用。
 * 启用后网关自身提供 /oauth2/token、/oauth2/jwks 等端点，无需外部授权服务器。
 *
 * 客户端注册信息持久化到数据库（oauth2_registered_client 表），
 * 通过管理 API 动态增删改客户端，控制谁有权申请 token。
 */
@Configuration
@ConditionalOnProperty(name = "mcp.gateway.auth-mode", havingValue = "local")
public class LocalAuthorizationServerConfig {

    @Value("${mcp.gateway.canonical-uri:https://mcp-gateway.example.com}")
    private String canonicalUri;

    @Value("${mcp.gateway.jwk.keystore-path:}")
    private String keystorePath;

    @Value("${mcp.gateway.jwk.keystore-password:changeit}")
    private String keystorePassword;

    @Value("${mcp.gateway.jwk.key-alias:mcp-gateway-key}")
    private String keyAlias;

    @Value("${mcp.gateway.jwk.auto-generate-path:data/mcp-gateway-keystore.p12}")
    private String autoGeneratePath;

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LocalAuthorizationServerConfig.class);

    /**
     * 授权服务器的 SecurityFilterChain，优先级高于资源服务器的 chain。
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);
        http.getConfigurer(OAuth2AuthorizationServerConfigurer.class)
            .tokenRevocationEndpoint(Customizer.withDefaults())
            .tokenIntrospectionEndpoint(Customizer.withDefaults())
            .oidc(Customizer.withDefaults());
        return http.build();
    }

    /**
     * 基于数据库的客户端注册仓库。
     * 客户端信息存储在 oauth2_registered_client 表中，支持动态管理。
     */
    @Bean
    public RegisteredClientRepository registeredClientRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcRegisteredClientRepository(jdbcTemplate);
    }

    /**
     * 基于数据库的授权记录服务，记录已签发的 token。
     */
    @Bean
    public OAuth2AuthorizationService authorizationService(JdbcTemplate jdbcTemplate,
                                                           RegisteredClientRepository registeredClientRepository) {
        return new JdbcOAuth2AuthorizationService(jdbcTemplate, registeredClientRepository);
    }

    /**
     * 基于数据库的授权同意服务。
     */
    @Bean
    public OAuth2AuthorizationConsentService authorizationConsentService(JdbcTemplate jdbcTemplate,
                                                                         RegisteredClientRepository registeredClientRepository) {
        return new JdbcOAuth2AuthorizationConsentService(jdbcTemplate, registeredClientRepository);
    }

    /**
     * 授权服务器设置，issuer 指向网关自身地址。
     */
    @Bean
    public AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder()
            .issuer(canonicalUri)
            .build();
    }

    /**
     * 用于签发和验证 JWT 的 RSA 密钥对。
     * 优先从配置的 keystore 文件加载；若未配置则自动生成并持久化到磁盘，
     * 确保重启后密钥不变、多实例可共享同一密钥文件。
     */
    @Bean
    public JWKSource<SecurityContext> jwkSource() {
        KeyPair keyPair = loadOrGenerateKeyPair();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();
        RSAKey rsaKey = new RSAKey.Builder(publicKey)
            .privateKey(privateKey)
            .keyID(keyAlias)
            .build();
        JWKSet jwkSet = new JWKSet(rsaKey);
        return new ImmutableJWKSet<>(jwkSet);
    }

    private KeyPair loadOrGenerateKeyPair() {
        // 1. 尝试从显式配置的 keystore 加载
        if (keystorePath != null && !keystorePath.isBlank()) {
            log.info("Loading JWK RSA key from configured keystore: {}", keystorePath);
            return loadKeyPairFromKeystore(Paths.get(keystorePath));
        }

        // 2. 尝试从自动生成路径加载已有 keystore
        Path autoPath = Paths.get(autoGeneratePath);
        if (Files.exists(autoPath)) {
            log.info("Loading JWK RSA key from existing auto-generated keystore: {}", autoPath);
            return loadKeyPairFromKeystore(autoPath);
        }

        // 3. 首次启动：生成并持久化
        log.warn("No JWK keystore found. Generating new RSA key pair and persisting to: {}", autoPath);
        return generateAndPersistKeyPair(autoPath);
    }

    private KeyPair loadKeyPairFromKeystore(Path path) {
        try (InputStream is = Files.newInputStream(path)) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(is, keystorePassword.toCharArray());
            java.security.Key key = ks.getKey(keyAlias, keystorePassword.toCharArray());
            java.security.cert.Certificate cert = ks.getCertificate(keyAlias);
            if (key == null || cert == null) {
                throw new IllegalStateException("Key alias '" + keyAlias + "' not found in keystore: " + path);
            }
            return new KeyPair(cert.getPublicKey(), (RSAPrivateKey) key);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read keystore: " + path, e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load key from keystore: " + path, e);
        }
    }

    private KeyPair generateAndPersistKeyPair(Path path) {
        try {
            Files.createDirectories(path.getParent());

            // 使用 keytool 生成自签名证书和密钥对（跨 JDK 版本兼容）
            ProcessBuilder pb = new ProcessBuilder(
                "keytool", "-genkeypair",
                "-alias", keyAlias,
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "3650",
                "-storetype", "PKCS12",
                "-keystore", path.toAbsolutePath().toString(),
                "-storepass", keystorePassword,
                "-keypass", keystorePassword,
                "-dname", "CN=MCP Gateway, O=AgentPlatform"
            );
            pb.inheritIO();
            Process process = pb.start();
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IllegalStateException("keytool failed with exit code: " + exitCode);
            }

            log.info("JWK RSA keystore generated via keytool: {}", path);
            return loadKeyPairFromKeystore(path);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to generate and persist RSA key pair", ex);
        }
    }
}
