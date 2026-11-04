// Enumerate ghost-waiter states and classify the outcome of the first
// rt_mutex_adjust_prio_chain walk (the C1 fire path).
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

static sigjmp_buf jb; static void os(int s){(void)s; siglongjmp(jb,1);}
static const char *step;
#define G(stmt, name) do { step = name; if (sigsetjmp(jb,1)==0) { stmt; } else { return name; } } while (0)

static int rb_empty(waiter_t *w){ return w->tree_entry.pc == (unsigned long)&w->tree_entry; }

static const char *chain(task_t *t, waiter_t *w, rt_mutex_t *next_lock) {
    waiter_t *waiter; rt_mutex_t *lock;
    G(waiter = t->pi_blocked_on, "read pi_blocked_on");
    if (!waiter) return "bail:no-waiter";
    G(if (next_lock != (rt_mutex_t*)waiter->lock) return "bail:next_lock", "read waiter->lock");
    if (waiter->prio == t->prio) return "bail:prio-equal";
    G(lock = (rt_mutex_t*)waiter->lock, "load-lock");
    G(volatile int l = lock->wait_lock; (void)l, "crash:trylock");
    G(void *o = lock->owner; (void)o, "crash:owner");
    if (lock->owner == (void*)t) return "bail:owner==top";
    if (!rb_empty(waiter)) {
        G(volatile unsigned long pc = waiter->tree_entry.left ? waiter->tree_entry.left->pc : 0; (void)pc,
          "crash:dequeue-rb_erase");
        G(lock->waiters = (rb_node*)0, "crash:dequeue-root");
    }
    G(*((volatile rb_node**)&lock->waiters) = &waiter->tree_entry, "crash:enqueue-link");
    G(volatile unsigned long r = lock->waiters->pc; (void)r, "crash:enqueue-color");
    return "OK";
}

int main(void) {
    signal(SIGSEGV, os);
    const char *tree_pc_names[] = {"0", "self(RB_CLEAR)", "kptr"};
    const char *tree_child_names[] = {"0", "valid", "bogus"};
    const char *lock_names[] = {"fake", "bogus", "real-other"};
    const char *prio_names[] = {"0x82", "task-prio"};
    int counts[8]; const char *cats[8]; int nc = 0;
    for (int a=0;a<3;a++) for (int b=0;b<3;b++) for (int c=0;c<3;c++)
    for (int d=0;d<3;d++) for (int e=0;e<2;e++) {
        waiter_t w; memset(&w,0,sizeof w);
        rt_mutex_t fake; memset(&fake,0,sizeof fake);
        rt_mutex_t other; memset(&other,0,sizeof other); other.owner=(void*)0x7777;
        rb_node valid_node; memset(&valid_node,0,sizeof valid_node); valid_node.pc=(unsigned long)&valid_node;
        w.tree_entry.pc = a==0?0:(a==1?(unsigned long)&w.tree_entry:0xffff0000UL);
        w.tree_entry.left = b==0?0:(b==1?&valid_node:(rb_node*)0xdead0000UL);
        w.tree_entry.right = c==0?0:(c==1?&valid_node:(rb_node*)0xbeef0000UL);
        w.lock = d==0?&fake:(d==1?(void*)0xdead0000UL:&other);
        w.prio = e==0?0x82:120;
        task_t t; memset(&t,0,sizeof t); t.pi_blocked_on=&w; t.prio=120;
        const char *r = chain(&t,&w,(rt_mutex_t*)w.lock);
        int found=-1;
        for (int i=0;i<nc;i++) if (!strcmp(cats[i],r)) { found=i; break; }
        if (found<0) { cats[nc]=r; counts[nc]=1; nc++; } else counts[found]++;
        if (a==0 && b==0 && c==0 && d==0)
            printf("tree=0,0,0 lock=fake prio=%s -> %s\n", prio_names[e], r);
        (void)tree_pc_names; (void)tree_child_names; (void)lock_names;
    }
    printf("\n-- outcome distribution over %d states --\n", 3*3*3*3*2);
    for (int i=0;i<nc;i++) printf("%-28s %d\n", cats[i], counts[i]);
    return 0;
}
