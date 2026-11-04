// Faithful port of the vendor rb_erase (non-augmented, case 1/2) with a tiny
// memory model, to trace WHERE the erase writes for our ghost's tree_entry.
#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <stdlib.h>

#define MEMSZ 4096
static unsigned long mem[MEMSZ];   // fake kernel memory: index = addr/8
static int faults;

static unsigned long *A(unsigned long addr) {  // translate, record fault
    if (addr == 0) { faults++; return NULL; }
    unsigned long i = addr / 8;
    if (i >= MEMSZ) { faults++; return NULL; }
    return &mem[i];
}
static unsigned long RD(unsigned long a){ unsigned long*p=A(a); return p?*p:0; }
static void WR(unsigned long a, unsigned long v){ unsigned long*p=A(a); if(p)*p=v; }

static unsigned long node;  // ghost address
static unsigned long root;  // &lock->waiters

static int is_black(unsigned long pc){ return pc & 1; }

static void change_child(unsigned long old, unsigned long newn, unsigned long parent, unsigned long rootaddr) {
    if (parent) {
        if (RD(parent + 16) == old) WR(parent + 16, newn);
        else WR(parent + 8, newn);
    } else WR(rootaddr, newn);
}

static void rb_erase(unsigned long nodeaddr, unsigned long rootaddr) {
    unsigned long child = RD(nodeaddr + 8);   // rb_right
    unsigned long tmp   = RD(nodeaddr + 16);  // rb_left
    unsigned long pc    = RD(nodeaddr + 0);   // __rb_parent_color
    unsigned long parent = pc & ~3UL;
    printf("    erase: node=%#lx pc=%#lx parent=%#lx left=%#lx right=%#lx\n",
           nodeaddr, pc, parent, tmp, child);
    if (!tmp) {
        change_child(nodeaddr, child, parent, rootaddr);
        printf("    case1: change_child(node -> %#lx at parent %#lx / root %#lx)\n", child, parent, rootaddr);
        if (child) WR(child, pc);
        return;
    }
    if (!child) {
        WR(tmp, pc);                       // tmp->__rb_parent_color = pc  <-- WRITE to left
        printf("    case2: WRITE pc=%#lx -> *left=%#lx\n", pc, tmp);
        change_child(nodeaddr, tmp, parent, rootaddr);
        printf("    case2: change_child(node -> left) at parent %#lx\n", parent);
        return;
    }
    printf("    case3 (both children): successor path not modelled\n");
}

static void run(const char *name, unsigned long pc, unsigned long left, unsigned long right) {
    memset(mem, 0, sizeof mem); faults = 0;
    unsigned long n = 0x1000, r = 0x2000;
    node = n; root = r;
    WR(n+0, pc); WR(n+8, right); WR(n+16, left);
    printf("%s\n", name);
    rb_erase(n, r);
    printf("    faults=%d root=%#lx\n\n", faults, RD(r));
}

int main(void) {
    printf("ghost node=0x1000 root(fake_lock.waiters)=0x2000\n\n");
    run("A) success ghost: tree={0,0,0}", 0, 0, 0);
    run("B) failure ghost: left=kernel ptr (0xffffff89eeb0af18)", 0, 0xffffff89eeb0af18UL, 0);
    run("C) tree.pc=dm-page target, left=0", 0x3000, 0, 0);
    run("D) tree.pc=dm-page target, left=dm-stamp", 0x3000, 0x4000, 0);
    run("E) tree.pc=bogus, left=0", 0xdead0000UL, 0, 0);
    return 0;
}
