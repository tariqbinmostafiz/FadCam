package com.fadcam.streaming;

import com.fadcam.Log;
import com.fadcam.FLog;
import android.content.Context;
import android.content.SharedPreferences;
import com.fadcam.Constants;
import com.fadcam.streaming.model.SessionToken;

import android.os.Build;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages authentication sessions for FadCam Remote access.
 * Handles token generation, validation, storage, and session lifecycle.
 * Thread-safe singleton implementation.
 */
public class RemoteAuthManager {
    private static final String TAG = "RemoteAuthManager";
    private static RemoteAuthManager instance;
    
    private final Context context;
    private final SharedPreferences prefs;
    private final Map<String, SessionToken> activeSessions;
    private final SecureRandom secureRandom;
    private volatile boolean sessionsJustCleared = false;
    
    private RemoteAuthManager(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE);
        this.activeSessions = new ConcurrentHashMap<>();
        this.secureRandom = new SecureRandom();
        loadSessionsFromStorage();
    }
    
    public static synchronized RemoteAuthManager getInstance(Context context) {
        if (instance == null) {
            instance = new RemoteAuthManager(context);
        }
        return instance;
    }
    
    /**
     * Check if authentication is enabled
     */
    public boolean isAuthEnabled() {
        return prefs.getBoolean(Constants.PREF_REMOTE_AUTH_ENABLED, false);
    }
    
    /**
     * Enable or disable authentication
     */
    public void setAuthEnabled(boolean enabled) {
        prefs.edit().putBoolean(Constants.PREF_REMOTE_AUTH_ENABLED, enabled).apply();
        if (!enabled) {
            clearAllSessions();
        }
        FLog.i(TAG, "Authentication " + (enabled ? "enabled" : "disabled"));
    }
    
    /**
     * Set password (stores SHA-256 hash)
     */
    public boolean setPassword(String password) {
        if (password == null) {
            FLog.w(TAG, "Password is null");
            return false;
        }
        
        // Trim whitespace
        password = password.trim();
        
        if (password.length() < Constants.REMOTE_AUTH_MIN_PASSWORD_LENGTH ||
            password.length() > Constants.REMOTE_AUTH_MAX_PASSWORD_LENGTH) {
            FLog.w(TAG, "Invalid password length: " + password.length());
            return false;
        }
        
        String hash = hashPassword(password);
        if (hash == null) {
            return false;
        }
        
        prefs.edit().putString(Constants.PREF_REMOTE_AUTH_PASSWORD_HASH, hash).apply();
        FLog.i(TAG, "Password updated successfully");
        
        // Invalidate all existing sessions when password changes
        clearAllSessions();
        return true;
    }
    
    /**
     * Verify password against stored hash
     */
    public boolean verifyPassword(String password) {
        if (password == null) {
            FLog.w(TAG, "Password is null");
            return false;
        }
        
        // Trim whitespace
        password = password.trim();
        
        String storedHash = prefs.getString(Constants.PREF_REMOTE_AUTH_PASSWORD_HASH, null);
        if (storedHash == null) {
            FLog.w(TAG, "No password set");
            return false;
        }
        
        boolean isValid;
        if (storedHash.startsWith(PBKDF2_PREFIX)) {
            isValid = verifyPbkdf2(storedHash, password);
        } else {
            // Legacy unsalted SHA-256 hash: verify (constant-time), then upgrade to PBKDF2.
            String legacy = legacySha256Hex(password);
            isValid = legacy != null && MessageDigest.isEqual(
                    storedHash.getBytes(StandardCharsets.UTF_8),
                    legacy.getBytes(StandardCharsets.UTF_8));
            if (isValid) {
                String upgraded = hashPassword(password);
                if (upgraded != null) {
                    prefs.edit().putString(Constants.PREF_REMOTE_AUTH_PASSWORD_HASH, upgraded).apply();
                    FLog.i(TAG, "Password hash upgraded to PBKDF2");
                }
            }
        }
        
        if (!isValid) {
            FLog.d(TAG, "Password verification failed: hash mismatch");
        }
        
        return isValid;
    }
    
    /**
     * Check if password is set
     */
    public boolean hasPassword() {
        String storedHash = prefs.getString(Constants.PREF_REMOTE_AUTH_PASSWORD_HASH, null);
        return storedHash != null && !storedHash.isEmpty();
    }
    
    /**
     * Generate new session token
     */
    public SessionToken createSession(String deviceInfo) {
        String token = generateToken();
        long now = System.currentTimeMillis();
        long expiresAt = now + Constants.REMOTE_AUTH_TOKEN_EXPIRY_MS;
        
        SessionToken session = new SessionToken(token, now, expiresAt, deviceInfo);
        activeSessions.put(token, session);
        saveSessionsToStorage();
        
        FLog.i(TAG, "New session created: " + token.substring(0, 8) + "... (expires in 24h)");
        return session;
    }
    
    /**
     * Validate token and return session if valid
     */
    public SessionToken validateToken(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        
        SessionToken session = activeSessions.get(token);
        if (session == null) {
            FLog.d(TAG, "Token not found in active sessions");
            return null;
        }
        
        if (session.isExpired()) {
            FLog.d(TAG, "Token expired");
            activeSessions.remove(token);
            saveSessionsToStorage();
            return null;
        }
        
        return session;
    }
    
    /**
     * Invalidate specific session
     */
    public void revokeSession(String token) {
        if (activeSessions.remove(token) != null) {
            saveSessionsToStorage();
            FLog.i(TAG, "Session revoked: " + token.substring(0, 8) + "...");
        }
    }
    
    /**
     * Clear all active sessions
     */
    public void clearAllSessions() {
        int count = activeSessions.size();
        activeSessions.clear();
        saveSessionsToStorage();
        sessionsJustCleared = true;  // Set flag for real-time detection
        FLog.i(TAG, "Cleared " + count + " session(s)");
    }
    
    /**
     * Check if sessions were just cleared and reset the flag
     */
    public boolean checkAndResetSessionsClearedFlag() {
        boolean wasClearedJustNow = sessionsJustCleared;
        sessionsJustCleared = false;
        return wasClearedJustNow;
    }
    
    /**
     * Get all active sessions
     */
    public Map<String, SessionToken> getActiveSessions() {
        // Remove expired sessions
        activeSessions.entrySet().removeIf(entry -> entry.getValue().isExpired());
        saveSessionsToStorage();
        return new ConcurrentHashMap<>(activeSessions);
    }
    
    /**
     * Get auto-lock timeout in minutes (0 = never)
     */
    public int getAutoLockTimeout() {
        return prefs.getInt(Constants.PREF_REMOTE_AUTH_AUTO_LOCK_TIMEOUT, 0);
    }
    
    /**
     * Set auto-lock timeout in minutes (0 = never)
     */
    public void setAutoLockTimeout(int minutes) {
        prefs.edit().putInt(Constants.PREF_REMOTE_AUTH_AUTO_LOCK_TIMEOUT, minutes).apply();
        FLog.i(TAG, "Auto-lock timeout set to: " + (minutes == 0 ? "Never" : minutes + " minutes"));
    }
    
    /**
     * Generate cryptographically secure random token
     */
    private String generateToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }
    
    /**
     * Legacy hash (unsalted SHA-256) - kept only to verify/upgrade old stored passwords
     */
    private String legacySha256Hex(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(password.getBytes());
            
            // Convert to hex string
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            FLog.e(TAG, "SHA-256 not available", e);
            return null;
        }
    }
    
    /**
     * Save active sessions to SharedPreferences
     */
    private void saveSessionsToStorage() {
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        
        for (SessionToken session : activeSessions.values()) {
            if (!first) json.append(",");
            json.append(session.toJson());
            first = false;
        }
        
        json.append("]");
        prefs.edit().putString(Constants.PREF_REMOTE_AUTH_SESSIONS, json.toString()).apply();
    }
    
    /**
     * Load sessions from SharedPreferences
     */
    private void loadSessionsFromStorage() {
        String json = prefs.getString(Constants.PREF_REMOTE_AUTH_SESSIONS, "[]");
        
        if (json.equals("[]")) {
            return;
        }
        
        try {
            // Simple JSON array parsing
            json = json.substring(1, json.length() - 1); // Remove [ and ]
            
            if (json.trim().isEmpty()) {
                return;
            }
            
            // Split by },{
            String[] sessionJsons = json.split("\\},\\{");
            
            for (String sessionJson : sessionJsons) {
                // Add back braces if removed by split
                if (!sessionJson.startsWith("{")) sessionJson = "{" + sessionJson;
                if (!sessionJson.endsWith("}")) sessionJson = sessionJson + "}";
                
                SessionToken session = SessionToken.fromJson(sessionJson);
                if (session != null && session.isValid()) {
                    activeSessions.put(session.getToken(), session);
                }
            }
            
            FLog.i(TAG, "Loaded " + activeSessions.size() + " active session(s)");
        } catch (Exception e) {
            FLog.e(TAG, "Failed to load sessions from storage", e);
            activeSessions.clear();
        }
    }

    // ---- PBKDF2 (salted, iterated) ----
    private static final String PBKDF2_PREFIX = "pbkdf2$";
    private static final int PBKDF2_ITERATIONS = 120_000;
    private static final int PBKDF2_SALT_BYTES = 16;
    private static final int PBKDF2_KEY_BITS = 256;

    /** Format: pbkdf2$<alg>$<iterations>$<saltB64>$<hashB64>; alg "256" = HmacSHA256, "1" = HmacSHA1 (API < 26). */
    private String hashPassword(String password) {
        try {
            byte[] salt = new byte[PBKDF2_SALT_BYTES];
            new SecureRandom().nextBytes(salt);
            String alg = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? "256" : "1";
            byte[] dk = pbkdf2(password, salt, PBKDF2_ITERATIONS, alg);
            return PBKDF2_PREFIX + alg + "$" + PBKDF2_ITERATIONS + "$"
                    + Base64.encodeToString(salt, Base64.NO_WRAP) + "$"
                    + Base64.encodeToString(dk, Base64.NO_WRAP);
        } catch (GeneralSecurityException e) {
            FLog.e(TAG, "PBKDF2 hashing failed", e);
            return null;
        }
    }

    private boolean verifyPbkdf2(String stored, String password) {
        try {
            String[] p = stored.split("\\$");
            if (p.length != 5) return false;
            int iterations = Integer.parseInt(p[2]);
            byte[] salt = Base64.decode(p[3], Base64.NO_WRAP);
            byte[] expected = Base64.decode(p[4], Base64.NO_WRAP);
            byte[] actual = pbkdf2(password, salt, iterations, p[1]);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            FLog.e(TAG, "PBKDF2 verify failed", e);
            return false;
        }
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations, String alg)
            throws GeneralSecurityException {
        String name = "256".equals(alg) ? "PBKDF2WithHmacSHA256" : "PBKDF2WithHmacSHA1";
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, PBKDF2_KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(name).generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }
}
