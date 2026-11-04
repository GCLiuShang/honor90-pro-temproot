// Offline model of rt_mutex_adjust_pi -> rt_mutex_adjust_prio_chain,
// focused on: given a ghost waiter with fields X, at which step does the
// chain fail, and is there an earlier bail-out that avoids the crash?
#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <signal.h>
#include <setjmp.h>

typedef struct rb_node { unsigned long pc; struct rb_node *right, *left; } rb_node;
typedef struct waiter {
    rb_node tree_entry; rb_node pi_tree_entry;
    void *task; void *lock; int prio; unsigned long deadline;
} waiter_t;
typedef struct rt_mutex { volatile int wait_lock; rb_node *waiters; void *owner; } rt_mutex_t;
typedef struct task_s { waiter_t *pi_blocked_on; int prio; } task_t;

static sigjmp_buf jb; static void onsegv(int s){(void)s; siglongjmp(jb,1);}
static const char *step;

#define GUARD(stmt, name) do { step = name; if (sigsetjmp(jb,1)==0) { stmt; } else { return name; } } while (0)

static int rb_empty(waiter_t *w){ return w->tree_entry.pc == (unsigned long)&w->tree_entry; }

static const char *chain(task_t *task, waiter_t *w0, rt_mutex_t *next_lock) {
    waiter_t *waiter; rt_mutex_t *lock;
    GUARD(waiter = task->pi_blocked_on, "read pi_blocked_on");
    if (!waiter) return "bail: no waiter";
    GUARD(if (next_lock != (rt_mutex_t*)waiter->lock) return "bail: next_lock mismatch", "read waiter->lock");
    // rt_mutex_waiter_equal(waiter, task_to_waiter(task)): prio equality
    if (waiter->prio == task->prio) return "bail: waiter_equal (prio)";
    GUARD(lock = (rt_mutex_t*)waiter->lock, "load lock");
    GUARD(volatile int l = lock->wait_lock; (void)l, "trylock wait_lock");
    GUARD(void *o = lock->owner; (void)o, "read lock->owner");
    if (lock->owner == (void*)task) return "bail: owner==top_task (EDEADLK)";
    // requeue path: dequeue
    if (!rb_empty(waiter)) {
        GUARD(volatile unsigned long pc = waiter->tree_entry.left ? waiter->tree_entry.left->pc : 0; (void)pc,
              "dequeue: rb_erase(waiter->tree_entry)");
        GUARD(lock->waiters = (rb_node*)0, "dequeue: root update");
    }
    // enqueue
    GUARD(*((volatile rb_node**)&lock->waiters) = &waiter->tree_entry, "enqueue: rb_link_node");
    GUARD(volatile unsigned long r = lock->waiters->pc; (void)r, "enqueue: insert_color");
    return "OK (walk completes)";
}

static void run(const char *name, waiter_t *w, rt_mutex_t *fake, int bad_lock, int task_prio) {
    memset(w, 0, sizeof *w);
    memset(fake, 0, sizeof *fake);
    w->lock = fake; w->prio = 0x82; w->task = (void*)0x1000;
    switch (bad_lock) {
        case 0: break;
        case 1: w->lock = (void*)0xdead0000UL; break;
        case 2: fake->owner = (void*)0x2000; break;
    }
    task_t t; t.pi_blocked_on = w; t.prio = task_prio;
    const char *r = chain(&t, w, (rt_mutex_t*)w->lock);
    printf("%-52s -> %s\n", name, r);
}

int main(void) {
    signal(SIGSEGV, onsegv);
    waiter_t w; rt_mutex_t fake;

    run("healthy payload (tree=0, lock=fake, prio=0x82)", &w, &fake, 0, 120);
    { memset(&w,0,sizeof w); memset(&fake,0,sizeof fake);
      w.lock=&fake; w.prio=0x82; w.tree_entry.left=(rb_node*)0xdeadbeefUL;
      task_t t; t.pi_blocked_on=&w; t.prio=120;
      printf("%-52s -> %s\n", "corrupted tree.left + good lock", chain(&t,&w,&fake)); }
    { memset(&w,0,sizeof w); memset(&fake,0,sizeof fake);
      w.lock=&fake; w.prio=120; w.tree_entry.left=(rb_node*)0xdeadbeefUL;
      task_t t; t.pi_blocked_on=&w; t.prio=120;
      printf("%-52s -> %s\n", "corrupted tree.left + prio==task (bail?)", chain(&t,&w,&fake)); }
    run("bogus lock pointer", &w, &fake, 1, 120);
    printf("done\n");
    return 0;
}
