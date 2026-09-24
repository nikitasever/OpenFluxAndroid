// IUnifiedService.aidl
package io.github.p1neapplexpress.openflux;

interface IUnifiedService {
    boolean isVpnRunning();
    void    stopVpn();

    boolean isFServiceRunning();
    String  nativeError();
    void    stopOpenFluxNative();
    void    startOpenFluxNative(String transport, in String[] args, String encryptionKey, in String[] extraDocumentUrls, String poolUrl, String sessionCookie);
    void    startTun2Socks();
    int     getFd();
}
