// Offline model: for a ghost waiter with fields X, does the first
// rt_mutex_dequeue() survive? A faulting dereference is caught with SIGSEGV.
#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <signal.h>
#include <setjmp.h>

typedef struct rb_node { unsigned long pc; struct rb_node *right, *left; } rb_node;
typedef struct waiter {
    rb_node tree_entry;
    rb_node pi_tree_entry;
    void *task;
    void *lock;
    int prio;
    unsigned long deadline;
} waiter_t;

static sigjmp_buf jb;
static void onsegv(int s) { (void)s; siglongjmp(jb, 1); }

static void model_rb_erase(rb_node *node, rb_node **root) {
    rb_node *child = node->right, *tmp = node->left, *parent;
    parent = (rb_node *)(node->pc & ~3UL);
    if (!tmp) {
        if (child) child->pc = (child->pc & 3UL) | (unsigned long)parent;
        if (!parent) *root = child;
        else if (parent->left == node) parent->left = child;
        else parent->right = child;
        return;
    }
    volatile unsigned long v = tmp->pc;  // deref left child (faults if bogus)
    (void)v;
}

static int dequeue_ok(waiter_t *w, rb_node **root) {
    if (w->tree_entry.pc == (unsigned long)&w->tree_entry) return 1;
    if (sigsetjmp(jb, 1) == 0) { model_rb_erase(&w->tree_entry, root); return 1; }
    return 0;
}

static void test(const char *name, waiter_t *w, rb_node **root) {
    int ok = dequeue_ok(w, root);
    printf("%-46s -> %s\n", name, ok ? "SAFE" : "CRASH (bad deref)");
}

int main(void) {
    signal(SIGSEGV, onsegv);
    waiter_t w; rb_node *root = NULL;
    memset(&w,0,sizeof w); test("payload: tree_entry = {0,0,0}", &w, &root);
    memset(&w,0,sizeof w); w.tree_entry.pc=(unsigned long)&w.tree_entry;
    test("tree_entry RB_CLEAR_NODE (real dequeued)", &w, &root);
    memset(&w,0,sizeof w); w.tree_entry.left=(rb_node*)0xdeadbeefUL;
    test("tree_entry.left = bogus", &w, &root);
    memset(&w,0,sizeof w); w.tree_entry.right=(rb_node*)0x1234UL;
    test("tree_entry.right = bogus", &w, &root);
    memset(&w,0,sizeof w); w.tree_entry.pc=(unsigned long)&w.tree_entry|1;
    test("tree_entry RB_EMPTY + black", &w, &root);
    return 0;
}
