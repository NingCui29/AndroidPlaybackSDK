
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
    setenv("LD_LIBRARY_PATH","/apex/com.android.runtime/lib64/bionic:/apex/com.android.i18n/lib64:/system/lib64:/apex/com.android.runtime/lib64",1);
    int ok=1;
    const char *apk[]={"/probe/direct.apk","/probe/split.apk"};
    const char *offsets[]={"/probe/direct.txt","/probe/split.txt"};
    for (int i=0; i<2; ++i) {
        int status;
        pid_t pid=fork();
        if (!pid) { execl("/probe/loader","loader",apk[i],offsets[i],(char *)0); _exit(126); }
        if (pid<0 || waitpid(pid,&status,0)!=pid || !WIFEXITED(status) || WEXITSTATUS(status)) ok=0;
    }
    dprintf(1,"D06 APK-offset verification %s\n",ok?"PASS":"FAIL");
    reboot(RB_POWER_OFF); for (;;) pause();
}
