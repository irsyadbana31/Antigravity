package id.vern.antigravity.shizuku;

interface IShizukuShellService {
    void destroy() = 16777114;
    void exit() = 1;
    int getUid() = 2;
    String execCommand(String cmd, long timeoutMs, int maxOutputBytes) = 3;
}
