#include "vmlinux.h"
#include <bpf/bpf_helpers.h>
#include <bpf/bpf_tracing.h>
#include <bpf/bpf_core_read.h>

#define AF_INET 2
#define AF_INET6 10
#define TCP_ESTABLISHED 1
#define TCP_SYN_SENT 2
#define TCP_CLOSE 7
#define CONN_OPEN 1
#define CONN_CLOSE 2
#define DIR_OUT 1
#define DIR_IN 2

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

struct tuple {
	__u16 family;
	__u16 sport;
	__u16 dport;
	__u8 saddr[16];
	__u8 daddr[16];
};

struct start {
	__u64 ts;
	__u64 cgroup;
	__u32 pid;
	__u8 dir;
	struct tuple t;
	char comm[16];
};

struct conn {
	__u64 duration_ns;
	__u64 rx;
	__u64 tx;
	__u64 cgroup;
	__u32 pid;
	__u16 family;
	__u16 sport;
	__u16 dport;
	__u8 kind;
	__u8 dir;
	__u8 saddr[16];
	__u8 daddr[16];
	char comm[16];
};

const struct conn *unused_conn __attribute__((unused));

struct {
	__uint(type, BPF_MAP_TYPE_HASH);
	__uint(max_entries, 65536);
	__type(key, __u64);
	__type(value, struct start);
} starts SEC(".maps");

struct {
	__uint(type, BPF_MAP_TYPE_RINGBUF);
	__uint(max_entries, 1 << 20);
} conns SEC(".maps");

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

static __always_inline void own(struct start *st)
{
	st->pid = current_pid();
	st->cgroup = bpf_get_current_cgroup_id();
	bpf_get_current_comm(&st->comm, sizeof(st->comm));
}

static __always_inline void emit(struct start *st, __u8 kind, __u64 rx, __u64 tx)
{
	struct conn *e = bpf_ringbuf_reserve(&conns, sizeof(*e), 0);
	if (!e)
		return;
	e->kind = kind;
	e->dir = st->dir;
	e->duration_ns = bpf_ktime_get_ns() - st->ts;
	e->rx = rx;
	e->tx = tx;
	e->pid = st->pid;
	e->cgroup = st->cgroup;
	e->family = st->t.family;
	e->sport = st->t.sport;
	e->dport = st->t.dport;
	__builtin_memcpy(e->saddr, st->t.saddr, sizeof(e->saddr));
	__builtin_memcpy(e->daddr, st->t.daddr, sizeof(e->daddr));
	__builtin_memcpy(e->comm, st->comm, sizeof(e->comm));
	bpf_ringbuf_submit(e, 0);
}

static __always_inline void read_tuple(struct trace_event_raw_inet_sock_set_state *ctx, struct tuple *t)
{
	__u32 *src = (__u32 *)t->saddr;
	__u32 *dst = (__u32 *)t->daddr;

	t->family = ctx->family;
	t->sport = ctx->sport;
	t->dport = ctx->dport;
	if (ctx->family == AF_INET) {
		src[0] = *(__u32 *)ctx->saddr;
		dst[0] = *(__u32 *)ctx->daddr;
		src[1] = src[2] = src[3] = 0;
		dst[1] = dst[2] = dst[3] = 0;
	} else {
		src[0] = *(__u32 *)&ctx->saddr_v6[0];
		src[1] = *(__u32 *)&ctx->saddr_v6[4];
		src[2] = *(__u32 *)&ctx->saddr_v6[8];
		src[3] = *(__u32 *)&ctx->saddr_v6[12];
		dst[0] = *(__u32 *)&ctx->daddr_v6[0];
		dst[1] = *(__u32 *)&ctx->daddr_v6[4];
		dst[2] = *(__u32 *)&ctx->daddr_v6[8];
		dst[3] = *(__u32 *)&ctx->daddr_v6[12];
	}
}

SEC("tracepoint/sock/inet_sock_set_state")
int sock_state(struct trace_event_raw_inet_sock_set_state *ctx)
{
	if (ctx->protocol != IPPROTO_TCP)
		return 0;
	if (ctx->family != AF_INET && ctx->family != AF_INET6)
		return 0;

	__u64 sk = (__u64)ctx->skaddr;
	int state = ctx->newstate;
	struct start *st = bpf_map_lookup_elem(&starts, &sk);

	if (state == TCP_SYN_SENT || (state == TCP_ESTABLISHED && !st)) {
		if (st)
			return 0;
		struct start fresh = {};
		fresh.ts = bpf_ktime_get_ns();
		read_tuple(ctx, &fresh.t);
		if (state == TCP_SYN_SENT) {
			fresh.dir = DIR_OUT;
			own(&fresh);
		} else {
			fresh.dir = DIR_IN;
		}
		bpf_map_update_elem(&starts, &sk, &fresh, BPF_ANY);
		return 0;
	}
	if (!st)
		return 0;

	if (state == TCP_ESTABLISHED && st->dir == DIR_OUT) {
		read_tuple(ctx, &st->t);
		emit(st, CONN_OPEN, 0, 0);
	} else if (state == TCP_CLOSE) {
		struct tcp_sock *tp = (struct tcp_sock *)ctx->skaddr;
		emit(st, CONN_CLOSE, BPF_CORE_READ(tp, bytes_received), BPF_CORE_READ(tp, bytes_acked));
		bpf_map_delete_elem(&starts, &sk);
	}
	return 0;
}

SEC("kretprobe/inet_csk_accept")
int BPF_KRETPROBE(inet_csk_accept, struct sock *sk)
{
	__u64 key = (__u64)sk;
	struct start *st = bpf_map_lookup_elem(&starts, &key);
	if (!st || st->dir != DIR_IN)
		return 0;
	own(st);
	emit(st, CONN_OPEN, 0, 0);
	return 0;
}
