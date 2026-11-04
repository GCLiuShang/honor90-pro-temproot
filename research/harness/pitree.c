// Model rt_mutex_dequeue_pi(task, waiter): rb_erase(&waiter->pi_tree_entry,
// &task->pi_waiters). Payload layout: pi_tree.pc = value (w3),
// pi_tree.right = 0 (w4), pi_tree.left = target (w5). The erase must write
// 'value' into *target -- this is the actual hijack primitive if it works.
#include <stdio.h>
#include <stdint.h>
#include <string.h>

#define MEMSZ 8192
static unsigned long mem[MEMSZ];
static int faults;
static unsigned long *A(unsigned long a){ if(!a){faults++;return NULL;} unsigned long i=a/8; if(i>=MEMSZ){faults++;return NULL;} return &mem[i]; }
static unsigned long RD(unsigned long a){ unsigned long*p=A(a); return p?*p:0; }
static void WR(unsigned long a,unsigned long v){ unsigned long*p=A(a); if(p)*p=v; }

static void change_child(unsigned long old,unsigned long newn,unsigned long parent,unsigned long root){
    if(parent){ if(RD(parent+16)==old) WR(parent+16,newn); else WR(parent+8,newn); }
    else WR(root,newn);
}
static void rb_erase(unsigned long node,unsigned long root){
    unsigned long child=RD(node+8), tmp=RD(node+16), pc=RD(node), parent=pc&~3UL;
    printf("    pi_tree: node=%#lx pc=%#lx left=%#lx right=%#lx parent=%#lx\n",node,pc,tmp,child,parent);
    if(!tmp){ change_child(node,child,parent,root); printf("    case1 -> change_child at %#lx (no left write)\n",parent); return; }
    if(!child){ WR(tmp,pc); printf("    case2 -> WRITE %#lx to *%#lx  (target write)\n",pc,tmp); change_child(node,tmp,parent,root); printf("    case2 -> change_child at %#lx\n",parent); return; }
    printf("    case3 not modelled\n");
}
int main(void){
    unsigned long node=0x1000, task=0x8000, root=0x8100; // task->pi_waiters
    unsigned long value=0xffffffe471011360UL, target=0xffffffe47144a6c8UL; // from a real run
    memset(mem,0,sizeof mem); faults=0;
    WR(node+0,value); WR(node+8,0); WR(node+16,target);
    printf("payload: pi_pc(value)=%#lx pi_right=0 pi_left(target)=%#lx\n",value,target);
    rb_erase(node,root);
    printf("faults=%d\n",faults);
    return 0;
}
