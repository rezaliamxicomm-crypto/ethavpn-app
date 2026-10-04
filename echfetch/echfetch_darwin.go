//go:build darwin

package libv2ray

// iOS (and macOS): binding a socket to an interface, so a fetch can leave beside a running tunnel.
// The build tag darwin covers ios. The same socket option Xray's own sockopt.interface sets.

import (
	"net"
	"strings"
	"syscall"
)

func init() { echBindToInterface = echBindDarwin }

func echBindDarwin(name string) (echSocketControl, error) {
	ifi, err := net.InterfaceByName(name)
	if err != nil {
		return nil, err
	}
	index := ifi.Index
	return func(network, address string, c syscall.RawConn) error {
		var serr error
		err := c.Control(func(fd uintptr) {
			if strings.HasSuffix(network, "6") {
				serr = syscall.SetsockoptInt(int(fd), syscall.IPPROTO_IPV6, syscall.IPV6_BOUND_IF, index)
			} else {
				serr = syscall.SetsockoptInt(int(fd), syscall.IPPROTO_IP, syscall.IP_BOUND_IF, index)
			}
		})
		if err != nil {
			return err
		}
		return serr
	}, nil
}
