#include <stdio.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/wait.h>
#include <sys/mount.h>
#include <sys/reboot.h>
#include <stdlib.h>
static int run(const char *file,const char *a,const char *b) {
    pid_t pid=fork();
    if(pid==0) {execl("/sdk/packet_reference","packet_reference",file,a,b,(char*)0);_exit(126);}
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
    int ok=run("/V04.mkv","/V04-0.ref","/V04-1.ref");
    ok &=run("/V11.mp4","/V11-0.ref",0);
    ok &=run("/V05.mkv","/V05-0.ref","/V05-2.ref");
    ok &=run("/V01.mp4","/V01-0.ref","/V01-1.ref");
    dprintf(1,"I03 record comparison overall=%s\n",ok?"PASS":"FAIL");
    reboot(RB_POWER_OFF);for(;;)pause();
}
