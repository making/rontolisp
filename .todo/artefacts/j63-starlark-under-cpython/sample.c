// sample.c: a ptrace sampler for wasmtime's JIT code. Runs the command, stops every
// thread each INTERVAL microseconds and writes, per sample of a thread that was on a
// CPU, its program counter, the return addresses up its frame-pointer chain and the
// first words of its stack (for a host frame without a frame pointer).
// usage: sample OUT INTERVAL_US command args...
#define _GNU_SOURCE
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ptrace.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/user.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define MAXT 256
static pid_t tids[MAXT];
static unsigned long long lastcpu[MAXT];
static int nt = 0;
static pid_t pid;
static FILE *out;

static void add(pid_t t) { for (int i = 0; i < nt; i++) if (tids[i] == t) return; if (nt < MAXT) { tids[nt] = t; lastcpu[nt] = 0; nt++; } }
static void del(pid_t t) { for (int i = 0; i < nt; i++) if (tids[i] == t) { tids[i] = tids[nt - 1]; lastcpu[i] = lastcpu[nt - 1]; nt--; return; } }

static int running(pid_t t) {
  char p[64], buf[512]; snprintf(p, sizeof p, "/proc/%d/task/%d/stat", pid, t);
  FILE *f = fopen(p, "r"); if (!f) return 0;
  size_t n = fread(buf, 1, sizeof buf - 1, f); fclose(f); buf[n] = 0;
  char *q = strrchr(buf, ')'); return q && q[1] == ' ' && q[2] == 'R';
}

// Handles one wait status of thread T; returns 1 when it is our SIGSTOP stop.
static int handle(pid_t t, int st, int expecting) {
  if (WIFEXITED(st) || WIFSIGNALED(st)) { del(t); if (t == pid) { fclose(out); exit(WIFEXITED(st) ? WEXITSTATUS(st) : 1); } return 0; }
  if (!WIFSTOPPED(st)) return 0;
  int sig = WSTOPSIG(st), ev = st >> 16;
  if (ev == PTRACE_EVENT_CLONE || ev == PTRACE_EVENT_FORK || ev == PTRACE_EVENT_VFORK) {
    unsigned long nt_ = 0; ptrace(PTRACE_GETEVENTMSG, t, 0, &nt_); add((pid_t) nt_);
    ptrace(PTRACE_CONT, t, 0, 0); return 0;
  }
  if (ev) { ptrace(PTRACE_CONT, t, 0, 0); return 0; }
  if (sig == SIGSTOP) { add(t); if (expecting) return 1; ptrace(PTRACE_CONT, t, 0, 0); return 0; }
  if (sig == SIGTRAP) { ptrace(PTRACE_CONT, t, 0, 0); return 0; }
  ptrace(PTRACE_CONT, t, 0, sig); return 0;
}

int main(int argc, char **argv) {
  if (argc < 4) { fprintf(stderr, "usage: sample OUT INTERVAL_US cmd...\n"); return 2; }
  out = fopen(argv[1], "w"); long interval = atol(argv[2]);
  pid = fork();
  if (pid == 0) { ptrace(PTRACE_TRACEME, 0, 0, 0); raise(SIGSTOP); execvp(argv[3], argv + 3); perror("exec"); _exit(127); }
  int st; waitpid(pid, &st, __WALL);
  ptrace(PTRACE_SETOPTIONS, pid, 0, PTRACE_O_TRACECLONE | PTRACE_O_TRACEFORK | PTRACE_O_TRACEVFORK | PTRACE_O_EXITKILL);
  add(pid); ptrace(PTRACE_CONT, pid, 0, 0);
  fprintf(out, "pid %d\n", pid);
  struct timespec ts = { interval / 1000000, (interval % 1000000) * 1000 };
  for (;;) {
    nanosleep(&ts, 0);
    // drain pending events
    for (;;) { pid_t t = waitpid(-1, &st, __WALL | WNOHANG); if (t <= 0) break; handle(t, st, 0); }
    for (int i = 0; i < nt; i++) {
      pid_t t = tids[i];
      if (!running(t)) continue; // not on a CPU
      if (syscall(SYS_tgkill, pid, t, SIGSTOP) != 0) continue;
      int got = 0;
      for (;;) { pid_t w = waitpid(t, &st, __WALL); if (w < 0) break; if (handle(t, st, 1)) { got = 1; break; } if (WIFEXITED(st) || WIFSIGNALED(st)) break; }
      if (!got) continue;
      struct user_regs_struct r;
      if (ptrace(PTRACE_GETREGS, t, 0, &r) == 0) {
        fprintf(out, "S %llx", r.rip);
        unsigned long long bp = r.rbp;
        for (int d = 0; d < 48 && bp > 0x1000; d++) {
          errno = 0; unsigned long long ret = ptrace(PTRACE_PEEKDATA, t, (void *) (bp + 8), 0); if (errno) break;
          unsigned long long nb = ptrace(PTRACE_PEEKDATA, t, (void *) bp, 0); if (errno) break;
          fprintf(out, " %llx", ret); if (nb <= bp) break; bp = nb;
        }
        fprintf(out, " |");
        for (int d = 0; d < 64; d++) { errno = 0; unsigned long long v = ptrace(PTRACE_PEEKDATA, t, (void *) (r.rsp + 8 * d), 0); if (errno) break; fprintf(out, " %llx", v); }
        fprintf(out, "\n");
      }
      ptrace(PTRACE_CONT, t, 0, 0);
    }
  }
}
