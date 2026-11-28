# 00-env.sh -- core environment of the tlx prefix.
_agy_files_dir="$(dirname "$PREFIX")"
export HOME="${HOME:-$_agy_files_dir/home}"
export TMPDIR="$_agy_files_dir/tmp"
export PATH="$PREFIX/bin:$_agy_files_dir/engine/bin:$PREFIX/libexec/git-core:/system/bin${PATH:+:$PATH}"
export LD_LIBRARY_PATH="$PREFIX/lib:/system/lib64"
unset LD_PRELOAD
export TERMUX__PREFIX="$PREFIX"
export TERMUX__ROOTFS="/"
export TERMUX_APP__DATA_DIR="$_agy_files_dir"
# Bridges set the package id for core-launched tools; derive it for direct shells.
if [ -z "${AGY_APPLICATION_ID:-}" ]; then
    _agy_pkg_dir="$(dirname "$_agy_files_dir")"
    AGY_APPLICATION_ID="$(basename "$_agy_pkg_dir")"
    unset _agy_pkg_dir
fi
export AGY_APPLICATION_ID
if [ -z "${AGY_GATEWAY_PORT:-}" ] && [ -r "$_agy_files_dir/logs/gateway.port" ]; then
    AGY_GATEWAY_PORT="$(tr -d '[:space:]' < "$_agy_files_dir/logs/gateway.port" 2>/dev/null || true)"
fi
[ -n "${AGY_GATEWAY_PORT:-}" ] && export AGY_GATEWAY_PORT
if [ -z "${AGY_GATEWAY_TOKEN:-}" ] && [ -r "$_agy_files_dir/logs/gateway.token" ]; then
    AGY_GATEWAY_TOKEN="$(tr -d '[:space:]' < "$_agy_files_dir/logs/gateway.token" 2>/dev/null || true)"
fi
[ -n "${AGY_GATEWAY_TOKEN:-}" ] && export AGY_GATEWAY_TOKEN
if [ -z "${AGY_SHIZUKU_ENABLED:-}" ] && [ -r "$_agy_files_dir/logs/shizuku.conf" ]; then
    AGY_SHIZUKU_ENABLED="$(sed -n 's/^SHIZUKU_ENABLED=//p' "$_agy_files_dir/logs/shizuku.conf" 2>/dev/null | head -n 1 | tr -d '[:space:]' || true)"
    AGY_SHIZUKU_BACKEND="$(sed -n 's/^SHIZUKU_BACKEND=//p' "$_agy_files_dir/logs/shizuku.conf" 2>/dev/null | head -n 1 | tr -d '[:space:]' || true)"
fi
export AGY_SHIZUKU_ENABLED="${AGY_SHIZUKU_ENABLED:-0}"
export AGY_SHIZUKU_BACKEND="${AGY_SHIZUKU_BACKEND:-auto}"
unset _agy_files_dir
export SSL_CERT_FILE="$PREFIX/etc/tls/cert.pem"
export CURL_CA_BUNDLE="$PREFIX/etc/tls/cert.pem"
export GIT_SSL_CAINFO="$PREFIX/etc/tls/cert.pem"
export GIT_CONFIG_NOSYSTEM=1
export GIT_ATTR_NOSYSTEM=1
export GIT_TEMPLATE_DIR="$PREFIX/share/git-core/templates"
export GIT_EXEC_PATH="$PREFIX/libexec/git-core"
export PAGER="less"
export OPENSSL_CONF="$PREFIX/etc/tls/openssl.cnf"
export LANG="${LANG:-C.UTF-8}"
export TZ="${TZ:-UTC}"
# CPU & toolchain compatibility: baseline ARMv8.0-A with dynamic outline atomics
export CFLAGS="-march=armv8-a -moutline-atomics ${CFLAGS:-}"
export CXXFLAGS="-march=armv8-a -moutline-atomics ${CXXFLAGS:-}"
mkdir -p "$TMPDIR" "$HOME" 2>/dev/null || true
