
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/mount.h>
#include <sys/reboot.h>
#include <sys/wait.h>
#include <unistd.h>
int main(void) {
    mount("proc","/proc","proc",0,0);
    int tty=open("/dev/console",O_RDWR);
    if (tty >= 0) { dup2(tty,0); dup2(tty,1); dup2(tty,2); }
    setenv("ANDROID_ROOT","/system",1); setenv("ANDROID_DATA","/data",1);
    setenv("LD_LIBRARY_PATH","/probe:/apex/com.android.runtime/lib64/bionic:/apex/com.android.i18n/lib64:/system/lib64:/apex/com.android.runtime/lib64",1);

    pid_t p=fork(); int status=0;
    if (!p) {execl("/probe/check","check",(char*)0);_exit(126);}
    int ok=p>0 && waitpid(p,&status,0)==p && WIFEXITED(status) && WEXITSTATUS(status)==0;
    dprintf(1,"H04 native candidate cache %s\n",ok?"PASS":"FAIL");
    reboot(RB_POWER_OFF); for (;;) pause();
}
