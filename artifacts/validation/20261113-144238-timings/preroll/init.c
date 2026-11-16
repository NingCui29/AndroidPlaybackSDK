#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/mount.h>
#include <sys/reboot.h>
#include <sys/wait.h>
#include <unistd.h>
int main(void){
    mount("proc","/proc","proc",0,0); mount("sysfs","/sys","sysfs",0,0);
    int tty=open("/dev/console",O_RDWR);
    if(tty>=0){dup2(tty,0);dup2(tty,1);dup2(tty,2);}
    setenv("ANDROID_ROOT","/system",1);setenv("ANDROID_DATA","/data",1);
    setenv("LD_LIBRARY_PATH","/sdk:/apex/com.android.runtime/lib64/bionic:/apex/com.android.i18n/lib64:/system/lib64:/apex/com.android.runtime/lib64",1);
    int ok=1;const char *targets[]={"7010","7522"};
    for(int i=0;i<2;i++){int status;pid_t p=fork(); if(p==0){execl("/sdk/check","check","/sample.mp4",targets[i],(char*)0);_exit(126);}
        ok &= p>0 && waitpid(p,&status,0)==p && WIFEXITED(status) && WEXITSTATUS(status)==0;}
    dprintf(1,"S04 AVC frame comparison overall=%s\n",ok?"PASS":"FAIL");
    reboot(RB_POWER_OFF);for(;;)pause();
}
