
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/mount.h>
#include <sys/reboot.h>
#include <sys/wait.h>
#include <unistd.h>
static int run(char *const args[]) {
    int status;
    pid_t child=fork();
    if (!child) { execv(args[0],args); _exit(126); }
    return child>0 && waitpid(child,&status,0)==child && WIFEXITED(status) && WEXITSTATUS(status)==0;
}
int main(void) {
    mount("proc","/proc","proc",0,0);
    int tty=open("/dev/console",O_RDWR);
    if (tty>=0) { dup2(tty,0); dup2(tty,1); dup2(tty,2); }
    setenv("ANDROID_ROOT","/system",1); setenv("ANDROID_DATA","/data",1);
    setenv("LD_LIBRARY_PATH","/probe:/apex/com.android.runtime/lib64/bionic:/apex/com.android.i18n/lib64:/system/lib64:/apex/com.android.runtime/lib64",1);
    int ok=run((char *const[]){"/probe/rgba",NULL});
    ok &= run((char *const[]){"/probe/pcm","/probe/A04.wav","/probe/A02.m4a","4c0c474c8a7ebdc3c023d250f82adcfa030f2b5b564ec788ad594e5b2d39f7a7",NULL});
    ok &= run((char *const[]){"/probe/cache",NULL});
    dprintf(1,"Native output verification page=%ld %s\n",sysconf(_SC_PAGESIZE),ok?"PASS":"FAIL");
    reboot(RB_POWER_OFF); for (;;) pause();
}
