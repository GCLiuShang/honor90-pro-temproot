// Compare the two chain paths for the same ghost states:
//   requeue=true  -> rt_mutex_dequeue(lock, waiter)  (rb_erase)
//   requeue=false -> skip dequeue, follow owner
#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <signal.h>
#include <setjmp.h>

typedef struct rb_node { unsigned long pc; struct rb_node *right, *left; } rb_node;
typedef struct waiter { rb_node tree_entry; rb_node pi_tree_entry; void *task, *lock; int prio; } waiter_t;
typedef struct rt_mutex { volatile int wait_lock; rb_node *waiters; void *owner; } rt_mutex_t;
typedef struct task_s { waiter_t *pi_blocked_on; int prio; } task_t;

static sigjmp_buf jb; static void os(int s){(void)s; siglongjmp(jb,1);}
static const char *step;
#define G(stmt,name) do{ step=name; if(sigsetjmp(jb,1)==0){stmt;} else {return name;} }while(0)
static int rb_empty(waiter_t*w){ return w->tree_entry.pc==(unsigned long)&w->tree_entry; }

static const char *walk(task_t *t, waiter_t *w, rt_mutex_t *lock, int requeue) {
    if (!requeue) {
        // [4] lock, [5] trylock, then skip [7]; follow owner
        G(volatile int l = lock->wait_lock; (void)l, "crash:trylock");
        G(void *o = lock->owner; (void)o, "crash:owner");
        if (!lock->owner) return "bail:no-owner(end)";
        return "OK:no-dequeue(walk continues)";
    }
    G(volatile int l = lock->wait_lock; (void)l, "crash:trylock");
    G(void *o = lock->owner; (void)o, "crash:owner");
    if (!rb_empty(w)) {
        G(volatile unsigned long pc = w->tree_entry.left ? w->tree_entry.left->pc : 0; (void)pc, "crash:dequeue");
    }
    G(*((volatile rb_node**)&lock->waiters) = &w->tree_entry, "crash:enqueue");
    return "OK:requeue-path";
}

int main(void) {
    signal(SIGSEGV, os);
    struct { const char *name; int treepc, left, right, lockbad; } S[] = {
        {"healthy           ", 0,0,0, 0},
        {"tree.left bogus   ", 0,2,0, 0},
        {"tree.right bogus  ", 0,0,2, 0},
        {"tree pc=kptr      ", 2,0,0, 0},
        {"lock bogus        ", 0,0,0, 1},
    };
    printf("%-20s | %-24s | %s\n","state","requeue=true","requeue=false");
    for (unsigned i=0;i<sizeof S/sizeof S[0];i++) {
        const char *r1,*r2;
        for (int q=0;q<2;q++) {
            waiter_t w; memset(&w,0,sizeof w); rt_mutex_t f; memset(&f,0,sizeof f);
            w.tree_entry.pc = S[i].treepc==0?0:(S[i].treepc==1?(unsigned long)&w.tree_entry:0xffff0000UL);
            w.tree_entry.left = S[i].left==0?0:(S[i].left==1?(rb_node*)&w.tree_entry:(rb_node*)0xdead0000UL);
            w.tree_entry.right= S[i].right==0?0:(S[i].right==1?(rb_node*)&w.tree_entry:(rb_node*)0xbeef0000UL);
            w.lock = S[i].lockbad ? (void*)0xdead0000UL : &f;
            w.prio = 0x82;
            task_t t; memset(&t,0,sizeof t); t.pi_blocked_on=&w; t.prio=120;
            const char *r = walk(&t,&w,(rt_mutex_t*)w.lock, q);
            if (q==1) r1=r; else r2=r; /* r1=requeue=true, r2=requeue=false */
        }
        printf("%-20s | %-24s | %s\n", S[i].name, r1, r2);
    }
    printf("\nrequeue=false is entered when top_waiter != task_top_pi_waiter(task)\nand deadlock detection is enabled; it skips rt_mutex_dequeue entirely.\n");
    return 0;
}
