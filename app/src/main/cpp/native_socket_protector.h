#pragma once

#ifdef __cplusplus
extern "C" {
#endif

// Returns true only when Android VpnService.protect(fd) succeeds.
// Must be called before TCP connect or UDP bind/connect.
bool abz_protect_socket_fd(int fd);

#ifdef __cplusplus
}
#endif
