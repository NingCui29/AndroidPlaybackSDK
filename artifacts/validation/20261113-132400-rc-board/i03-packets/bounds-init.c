#include <stdio.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/wait.h>
#include <sys/mount.h>
#include <sys/reboot.h>
#include <stdlib.h>
static int run(const char *program) {
    pid_t pid=fork();
    if(pid==0) {execl(program,program,(char*)0);_exit(126);}
    int status;
    return pid>0 && waitpid(pid,&status,0)==pid && WIFEXITED(status) && !WEXITSTATUS(status);
}
int main(void) {
    mount("proc","/proc","proc",0,0); mount("sysfs","/sys","sysfs",0,0);
    int tty=open("/dev/console",O_RDWR);
    if (tty>=0) { dup2(tty,0);dup2(tty,1);dup2(tty,2); }
    setenv("ANDROID_ROOT","/system",1);setenv("ANDROID_DATA","/data",1);
    setenv("LD_LIBRARY_PATH","/sdk:/apex/com.android.runtime/lib64/bionic:/apex/com.android.i18n/lib64:/system/lib64:/apex/com.android.runtime/lib64",1);
    dprintf(1,"I03 kernel page size=%ld\n",sysconf(_SC_PAGESIZE));
    int ok=run("/sdk/probe_limits"); ok &=run("/sdk/queue_checks"); dprintf(1,"I03 budgets overall=%s\n",ok?"PASS":"FAIL");
    reboot(RB_POWER_OFF);for(;;)pause();
}

