#include "vmlinux.h"
#include <bpf/bpf_helpers.h>
#include <bpf/bpf_tracing.h>

char LICENSE[] SEC("license") = "Dual BSD/GPL";

volatile const __u64 ns_dev;
volatile const __u64 ns_ino;

struct usage {
	__u64 tx;
	__u64 rx;
	__u64 cgroup;
	char comm[16];
};

struct {
	__uint(type, BPF_MAP_TYPE_HASH);
	__uint(max_entries, 16384);
	__type(key, __u32);
	__type(value, struct usage);
} usage SEC(".maps");

static __always_inline __u32 current_pid(void)
{
	struct bpf_pidns_info ns = {};

	if (ns_ino && !bpf_get_ns_current_pid_tgid(ns_dev, ns_ino, &ns, sizeof(ns)))
		return ns.tgid;
	return bpf_get_current_pid_tgid() >> 32;
}

static __always_inline void count(__u64 tx, __u64 rx)
{
	__u32 pid = current_pid();
	if (pid == 0)
		return;

	struct usage *u = bpf_map_lookup_elem(&usage, &pid);
	if (!u) {
		struct usage fresh = {};
		fresh.cgroup = bpf_get_current_cgroup_id();
		bpf_get_current_comm(&fresh.comm, sizeof(fresh.comm));
		bpf_map_update_elem(&usage, &pid, &fresh, BPF_NOEXIST);
		u = bpf_map_lookup_elem(&usage, &pid);
		if (!u)
			return;
	}
	if (tx)
		__sync_fetch_and_add(&u->tx, tx);
	if (rx)
		__sync_fetch_and_add(&u->rx, rx);
}

SEC("kprobe/tcp_sendmsg")
int BPF_KPROBE(tcp_sendmsg, struct sock *sk, struct msghdr *msg, size_t size)
{
	count(size, 0);
	return 0;
}

SEC("kprobe/tcp_cleanup_rbuf")
int BPF_KPROBE(tcp_cleanup_rbuf, struct sock *sk, int copied)
{
	if (copied > 0)
		count(0, copied);
	return 0;
}

SEC("kprobe/udp_sendmsg")
int BPF_KPROBE(udp_sendmsg, struct sock *sk, struct msghdr *msg, size_t len)
{
	count(len, 0);
	return 0;
}

SEC("kprobe/udpv6_sendmsg")
int BPF_KPROBE(udpv6_sendmsg, struct sock *sk, struct msghdr *msg, size_t len)
{
	count(len, 0);
	return 0;
}

SEC("kretprobe/udp_recvmsg")
int BPF_KRETPROBE(udp_recvmsg, int ret)
{
	if (ret > 0)
		count(0, ret);
	return 0;
}

SEC("kretprobe/udpv6_recvmsg")
int BPF_KRETPROBE(udpv6_recvmsg, int ret)
{
	if (ret > 0)
		count(0, ret);
	return 0;
}
