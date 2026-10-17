#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/socket.h>
#include <poll.h>
#include <arpa/inet.h>
#include <netinet/tcp.h>
#include <time.h>
#include <lwip/init.h>
#include <lwip/netif.h>
#include <lwip/timeouts.h>
#include <lwip/tcp.h>
#include <lwip/udp.h>
#include <lwip/ip6.h>
#include <lwip/stats.h>
#include <lwip/priv/nd6_priv.h>
#include <lwip/priv/tcp_priv.h>
#include <lwip/ethip6.h>
#include <netif/ethernet.h>

#define MAX_CONN 48
#define MAX_PORT 128
#define MAX_PEER_UDP 128
#define RX_CAP (256 * 1024)
#define TX_CAP 16384
#define QUEUE_CAP 1024
struct frame { struct frame *next; int size; uint64_t queued_ns; unsigned char data[]; };
struct connection {
  struct tcp_pcb *pcb; int fd, connecting, peer_eof, local_eof;
  unsigned char *rx; int rx_size;
  unsigned char tx[TX_CAP]; int tx_size;
  unsigned short port; unsigned long rx_total, tx_total, id, would_block; uint64_t rx_since, tx_since, progress_ns;
};
struct tcp_port { unsigned short port; struct tcp_pcb *pcb; int refs; u32_t seen; };
struct udp_port { unsigned short port; struct udp_pcb *pcb; int refs; u32_t seen; };
struct peer_udp { unsigned short port; int fd; u32_t seen; };
static struct connection conns[MAX_CONN];
static struct tcp_port tcp_ports[MAX_PORT]; static int ntcp;
static struct udp_port udp_ports[MAX_PORT]; static int nudp;
static struct peer_udp peer_udps[MAX_PEER_UDP]; static int npeer;
static struct netif link_netif;
static ip_addr_t peer_ip;
static int initialized, active, debug_port, peer_known;
static struct frame *qhead, *qtail; static int qcount, qbytes, qpeak; static unsigned long conn_ids, queue_full;
static char message_buffer[32768]; static int message_size;
static unsigned long frames_in, frames_out; static u32_t refresh_at;
static u32_t stats_at; static int packet_budget;
static char diagnostic_path[1024];
static uint64_t mono_ns(void) {struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return (uint64_t)t.tv_sec*1000000000+t.tv_nsec;}
struct timing {unsigned long bins[128],count,errors;uint64_t max;};
static struct timing scan_time,tcp_time,udp_time,poll_time,output_age,tcp_rx_age,tcp_tx_age;
static int diagnostics_enabled=1;
static uint64_t timer_last,timer_gap_max;
static unsigned int tcp_cursor,udp_cursor;
static FILE *scan_file;static int scan_index;static u32_t scan_next;
static const char *scan_paths[]={"/proc/net/tcp","/proc/net/tcp6","/proc/net/udp","/proc/net/udp6"};
static void timed(struct timing *t,uint64_t ns,int error){if(!diagnostics_enabled)return;uint64_t us=(ns+999)/1000;int i;
#if 0 /* Retained original linear bucket search. */
i=0;while(i<127&&((1ULL<<(i/4))*(4+i%4)/4)<us)i++;
#else
unsigned int e=63u-(unsigned int)__builtin_clzll(us?us:1);uint64_t base=1ULL<<e;
i=(int)(e*4+(us?((us-base)*4+base-1)/base:0));if(i>127)i=127;
#endif
t->bins[i]++;t->count++;t->errors+=!!error;if(ns>t->max)t->max=ns;}
static uint64_t quantile(struct timing *t,int p){unsigned long target=(t->count*p+99)/100,n=0;for(int i=0;i<128;i++){n+=t->bins[i];if(n>=target)return (1ULL<<(i/4))*(4+i%4)/4;}return t->max/1000;}

void diplay_assert(const char *message,const char *file,int line) {
 FILE *f=fopen(diagnostic_path,"a");if(f){fprintf(f,"LWIP_ASSERT %s at %s:%d\n",message,file,line);fclose(f);}
 fprintf(stderr,"LWIP_ASSERT %s at %s:%d\n",message,file,line);abort();
}

u32_t sys_now(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return (u32_t)(t.tv_sec*1000 + t.tv_nsec/1000000); }
uint32_t diplay_random(void) { uint32_t n; int f=open("/dev/urandom",O_RDONLY); if(f>=0){int r=read(f,&n,4);close(f);if(r==4)return n;}return (uint32_t)rand(); }
static void note(const char *fmt, ...) {
 if(message_size>30000)return; va_list ap;va_start(ap,fmt);
 int n=vsnprintf(message_buffer+message_size,sizeof(message_buffer)-message_size,fmt,ap);va_end(ap);
 if(n>0){message_size += n < (int)sizeof(message_buffer)-message_size ? n : (int)sizeof(message_buffer)-message_size-1;
 if(message_size<32766)message_buffer[message_size++]='\n';message_buffer[message_size]=0;}
}
static void timing_note(const char *name,struct timing *t){
 note("LWIP_NATIVE_TIMING name=%s n=%lu p50Us=%llu p95Us=%llu p99Us=%llu maxUs=%llu errors=%lu",name,t->count,(unsigned long long)(t->count?quantile(t,50):0),(unsigned long long)(t->count?quantile(t,95):0),(unsigned long long)(t->count?quantile(t,99):0),(unsigned long long)(t->max/1000),t->errors);memset(t,0,sizeof(*t));}
static err_t link_output(struct netif *netif, struct pbuf *p) {
 (void)netif; if(qcount>=QUEUE_CAP||qbytes+p->tot_len>1024*1024){queue_full++;return ERR_MEM;}
 struct frame *f=malloc(sizeof(*f)+p->tot_len);if(!f)return ERR_MEM;
 f->next=NULL;f->size=p->tot_len;f->queued_ns=mono_ns();qbytes+=f->size;if(qbytes>qpeak)qpeak=qbytes;pbuf_copy_partial(p,f->data,p->tot_len,0);
 if(packet_budget>0&&f->size>=55){unsigned char *b=f->data;note("LWIP_PACKET_OUT next=%u icmp=%u bytes=%d",b[20],b[54],f->size);packet_budget--;}
 if(qtail)qtail->next=f;else qhead=f;qtail=f;qcount++;frames_out++;return ERR_OK;
}
static err_t netif_init_link(struct netif *n) {
 n->name[0]='u';n->name[1]='c';n->mtu=1500;n->hwaddr_len=6;
 n->flags=NETIF_FLAG_BROADCAST|NETIF_FLAG_ETHERNET|NETIF_FLAG_MLD6;
 n->output_ip6=ethip6_output;n->linkoutput=link_output;return ERR_OK;
}
static void socket_nonblock(int fd) { fcntl(fd,F_SETFL,fcntl(fd,F_GETFL,0)|O_NONBLOCK); }
static struct sockaddr_in local_address(unsigned short port, int peer) {
 struct sockaddr_in a;memset(&a,0,sizeof(a));a.sin_family=AF_INET;a.sin_port=htons(port);a.sin_addr.s_addr=htonl(peer?0x7f000002:0x7f000001);return a;
}
static void forget_connection(struct connection *c) {
 if(c->fd>=0)close(c->fd);c->fd=-1;free(c->rx);c->rx=NULL;c->pcb=NULL;c->rx_size=0;c->tx_size=0;
}
static void on_tcp_error(void *arg, err_t error) { struct connection *c=arg;note("LWIP_TCP_END port=%u error=%d rx=%lu tx=%lu",c->port,error,c->rx_total,c->tx_total);forget_connection(c); }
static err_t on_tcp_recv(void *arg, struct tcp_pcb *pcb, struct pbuf *p, err_t error) {
 (void)pcb; struct connection *c=arg;if(error!=ERR_OK)return error;
 if(!p){c->peer_eof=1;return ERR_OK;}
 if(p->tot_len>RX_CAP-c->rx_size)return ERR_MEM;
 pbuf_copy_partial(p,c->rx+c->rx_size,p->tot_len,0);if(!c->rx_size)c->rx_since=mono_ns();c->rx_size+=p->tot_len;c->rx_total+=p->tot_len;pbuf_free(p);return ERR_OK;
}
static err_t on_tcp_accept(void *arg, struct tcp_pcb *pcb, err_t error) {
 struct tcp_port *port=arg;if(error!=ERR_OK)return error;
 struct connection *c=NULL;for(int i=0;i<MAX_CONN;i++)if(!conns[i].pcb){c=&conns[i];break;}
 if(!c){tcp_abort(pcb);return ERR_ABRT;}
 memset(c,0,sizeof(*c));c->fd=-1;c->pcb=pcb;c->port=port->port;c->id=++conn_ids;c->progress_ns=mono_ns();c->rx=malloc(RX_CAP);
 if(!c->rx){forget_connection(c);tcp_abort(pcb);return ERR_ABRT;}
 c->fd=socket(AF_INET,SOCK_STREAM,0);if(c->fd<0){forget_connection(c);tcp_abort(pcb);return ERR_ABRT;}
 socket_nonblock(c->fd);int one=1;setsockopt(c->fd,SOL_SOCKET,SO_REUSEADDR,&one,sizeof(one));
 setsockopt(c->fd,IPPROTO_TCP,TCP_NODELAY,&one,sizeof(one));
 struct sockaddr_in from=local_address(0,1),dest=local_address(port->port,0);
 if(bind(c->fd,(struct sockaddr*)&from,sizeof(from))<0){note("LWIP_LOCAL_BIND_FAIL errno=%d",errno);forget_connection(c);tcp_abort(pcb);return ERR_ABRT;}
 int result=connect(c->fd,(struct sockaddr*)&dest,sizeof(dest));
 if(result<0&&errno!=EINPROGRESS){note("LWIP_LOCAL_CONNECT_FAIL port=%u errno=%d",port->port,errno);forget_connection(c);tcp_abort(pcb);return ERR_ABRT;}
 c->connecting=result<0;tcp_arg(pcb,c);tcp_recv(pcb,on_tcp_recv);tcp_err(pcb,on_tcp_error);tcp_nagle_disable(pcb);
 note("LWIP_TCP_ACCEPT port=%u remote=%u connectionId=%lu",port->port,pcb->remote_port,c->id);return ERR_OK;
}
static struct tcp_port *add_tcp_port(unsigned short port) {
 if(!port||port==debug_port)return NULL;
 struct tcp_port *entry=NULL;
 for(int i=0;i<MAX_PORT;i++){if(tcp_ports[i].pcb&&tcp_ports[i].port==port){tcp_ports[i].seen=sys_now();return &tcp_ports[i];}if(!tcp_ports[i].pcb&&!entry)entry=&tcp_ports[i];}
 if(!entry){note("LWIP_PORT_FULL protocol=tcp");return NULL;}
 struct tcp_pcb *p=tcp_new_ip_type(IPADDR_TYPE_V6);if(!p)return NULL;
 if(tcp_bind(p,IP6_ADDR_ANY,port)!=ERR_OK){tcp_abort(p);return NULL;}
 struct tcp_pcb *listening=tcp_listen(p);if(!listening){tcp_abort(p);return NULL;}p=listening;
 entry->port=port;entry->pcb=p;entry->refs=0;entry->seen=sys_now();ntcp++;tcp_arg(p,entry);tcp_accept(p,on_tcp_accept);
 note("LWIP_TCP_LISTEN port=%u",port);return entry;
}
static void remove_tcp_port(struct tcp_port *e){if(!e->pcb)return;tcp_arg(e->pcb,NULL);tcp_accept(e->pcb,NULL);tcp_close(e->pcb);e->pcb=NULL;e->port=0;e->refs=0;ntcp--;}
static struct peer_udp *peer_socket(unsigned short port) {
 for(int i=0;i<MAX_PEER_UDP;i++)if(peer_udps[i].fd>=0&&peer_udps[i].port==port){peer_udps[i].seen=sys_now();return &peer_udps[i];}
 if(npeer>=MAX_PEER_UDP||!port)return NULL;
 int fd=socket(AF_INET,SOCK_DGRAM,0);if(fd<0)return NULL;socket_nonblock(fd);
 struct sockaddr_in a=local_address(port,1);if(bind(fd,(struct sockaddr*)&a,sizeof(a))<0){note("LWIP_UDP_BIND_FAIL port=%u errno=%d",port,errno);close(fd);return NULL;}
 struct peer_udp *e=NULL;for(int i=0;i<MAX_PEER_UDP;i++)if(peer_udps[i].fd<0){e=&peer_udps[i];break;}if(!e){close(fd);return NULL;}npeer++;e->port=port;e->fd=fd;e->seen=sys_now();note("LWIP_UDP_PEER port=%u",port);return e;
}
static void on_udp_recv(void *arg, struct udp_pcb *pcb, struct pbuf *p, const ip_addr_t *addr, u16_t port) {
 (void)pcb; struct udp_port *entry=arg;
 if(!p)return;struct peer_udp *peer=peer_socket(port);
 if(peer){unsigned char *b=malloc(p->tot_len);if(b){pbuf_copy_partial(p,b,p->tot_len,0);struct sockaddr_in dest=local_address(entry->port,0);
 int n=sendto(peer->fd,b,p->tot_len,0,(struct sockaddr*)&dest,sizeof(dest));if(n<0)note("LWIP_UDP_LOCAL_FAIL errno=%d",errno);free(b);}}
 ip_addr_copy(peer_ip,*addr);peer_known=1;pbuf_free(p);
}
static struct udp_port *add_udp_port(unsigned short port) {
 if(!port)return NULL;struct udp_port *e=NULL;
 for(int i=0;i<MAX_PORT;i++){if(udp_ports[i].pcb&&udp_ports[i].port==port){udp_ports[i].seen=sys_now();return &udp_ports[i];}if(!udp_ports[i].pcb&&!e)e=&udp_ports[i];}
 if(!e){note("LWIP_PORT_FULL protocol=udp");return NULL;}struct udp_pcb *p=udp_new_ip_type(IPADDR_TYPE_V6);if(!p)return NULL;
 if(udp_bind(p,IP6_ADDR_ANY,port)!=ERR_OK){udp_remove(p);return NULL;}
 e->port=port;e->pcb=p;e->refs=0;e->seen=sys_now();nudp++;udp_recv(p,on_udp_recv,e);return e;
}
static void remove_udp_port(struct udp_port *e){if(!e->pcb)return;udp_remove(e->pcb);e->pcb=NULL;e->port=0;e->refs=0;nudp--;}
static void scan_line(char *line,int udp){
 char *save=NULL,*tok=strtok_r(line," \t\n",&save),*local=NULL,*state=NULL,*uid=NULL;
 for(int col=0;tok;col++,tok=strtok_r(NULL," \t\n",&save)){if(col==1)local=tok;if(col==3)state=tok;if(col==7){uid=tok;break;}}
 if(!local||!state||!uid||(unsigned)strtoul(uid,NULL,10)!=(unsigned)getuid())return;
 if(!udp&&strcmp(state,"0A"))return;char *colon=strchr(local,':');if(!colon)return;*colon=0;
 if(!strcmp(local,"0200007F")||!strcmp(local,"0000000000000000FFFF00000200007F"))return;
 unsigned short port=(unsigned short)strtoul(colon+1,NULL,16);
 if(udp)add_udp_port(port);else add_tcp_port(port);
}
/* Fallback only: incremental scan, max 64 rows/1ms. Explicit port acks avoid scan races. */
static void scan_ports(void){
 uint64_t before=mono_ns();u32_t now=sys_now();if(!scan_file&&(s32_t)(now-scan_next)<0)return;
 if(!scan_file){scan_file=fopen(scan_paths[scan_index],"r");if(scan_file){char line[1024];fgets(line,sizeof(line),scan_file);}}
 if(scan_file){char line[1024];int rows=0;while(rows++<64&&mono_ns()-before<1000000){
  if(!fgets(line,sizeof(line),scan_file)){fclose(scan_file);scan_file=NULL;break;}scan_line(line,scan_index>=2);}}
 if(!scan_file){scan_index=(scan_index+1)%4;scan_next=now+(scan_index==0?1000:0);}
 timed(&scan_time,mono_ns()-before,0);
}
static void pump_tcp(struct connection *c) {
 if(!c->pcb)return;
 if (c->connecting) {
  // poll accepts process-wide descriptor numbers beyond FD_SETSIZE.
  struct pollfd pending = {.fd = c->fd, .events = POLLOUT};
  int ready = poll(&pending, 1, 0);
  if (ready == 0 || (ready < 0 && errno == EINTR)) return;
  int error = 0;
  socklen_t length = sizeof(error);
  if (ready < 0 || (pending.revents & POLLNVAL)) error = ready < 0 ? errno : EBADF;
  else if (getsockopt(c->fd, SOL_SOCKET, SO_ERROR, &error, &length) < 0) error = errno;
  if (error) {
   note("LWIP_LOCAL_CONNECT_FAIL port=%u errno=%d", c->port, error);
   tcp_abort(c->pcb);
   return;
  }
  c->connecting = 0;
  note("LWIP_LOCAL_CONNECTED port=%u", c->port);
 }

 if(c->rx_size){int n=send(c->fd,c->rx,c->rx_size>65535?65535:c->rx_size,MSG_NOSIGNAL);
  if(n>0){memmove(c->rx,c->rx+n,c->rx_size-n);c->rx_size-=n;c->progress_ns=mono_ns();if(!c->rx_size){timed(&tcp_rx_age,c->progress_ns-c->rx_since,0);c->rx_since=0;}tcp_recved(c->pcb,(u16_t)n);}
  else if(n<0&&(errno==EAGAIN||errno==EWOULDBLOCK))c->would_block++;
  else if(n<0&&errno!=EAGAIN&&errno!=EWOULDBLOCK&&errno!=EINTR){note("LWIP_LOCAL_SEND_FAIL port=%u errno=%d",c->port,errno);tcp_abort(c->pcb);return;}
 }
 if(c->peer_eof&&c->rx_size==0){shutdown(c->fd,SHUT_WR);c->peer_eof=2;}
 if(!c->tx_size&&!c->local_eof){int want=tcp_sndbuf(c->pcb);if(want>TX_CAP)want=TX_CAP;
  if(want>0){int n=recv(c->fd,c->tx,want,0);if(n>0){c->tx_size=n;c->tx_since=mono_ns();c->tx_total+=n;}else if(n==0)c->local_eof=1;
  else if(errno!=EAGAIN&&errno!=EWOULDBLOCK&&errno!=EINTR){note("LWIP_LOCAL_READ_FAIL port=%u errno=%d",c->port,errno);tcp_abort(c->pcb);return;}}
 }
 if(c->tx_size){int n=c->tx_size;if(n>tcp_sndbuf(c->pcb))n=tcp_sndbuf(c->pcb);
  if(n>0&&tcp_write(c->pcb,c->tx,n,TCP_WRITE_FLAG_COPY)==ERR_OK){memmove(c->tx,c->tx+n,c->tx_size-n);c->tx_size-=n;c->progress_ns=mono_ns();if(!c->tx_size){timed(&tcp_tx_age,c->progress_ns-c->tx_since,0);c->tx_since=0;}tcp_output(c->pcb);}}
 if(c->local_eof&&!c->tx_size&&!c->rx_size){struct tcp_pcb *p=c->pcb;tcp_arg(p,NULL);tcp_recv(p,NULL);tcp_err(p,NULL);
  if(tcp_close(p)==ERR_OK){note("LWIP_TCP_LOCAL_EOF port=%u rx=%lu tx=%lu",c->port,c->rx_total,c->tx_total);forget_connection(c);}
  else{tcp_arg(p,c);tcp_recv(p,on_tcp_recv);tcp_err(p,on_tcp_error);}}
}
static void poll_link(void) {
 if(!active)return;uint64_t begin=mono_ns();u32_t now=sys_now();scan_ports();
 uint64_t timer=mono_ns();if(timer_last&&timer-timer_last>timer_gap_max)timer_gap_max=timer-timer_last;timer_last=timer;sys_check_timeouts();
 uint64_t before=mono_ns();
 for(int k=0;k<MAX_CONN;k++){int i=tcp_cursor++%MAX_CONN;pump_tcp(&conns[i]);if(mono_ns()-before>1000000)break;}
 timed(&tcp_time,mono_ns()-before,0);
 before=mono_ns();int datagrams=0,bytes=0;
 if(peer_known){unsigned char buffer[65536];
 for(int k=0;k<MAX_PEER_UDP&&datagrams<64&&bytes<65536&&mono_ns()-before<1000000;k++){
  int i=udp_cursor++%MAX_PEER_UDP;if(peer_udps[i].fd<0)continue;
  for(int j=0;j<4&&datagrams<64&&bytes<65536&&mono_ns()-before<1000000;j++){
   struct sockaddr_in from;socklen_t length=sizeof(from);int n=recvfrom(peer_udps[i].fd,buffer,sizeof(buffer),0,(struct sockaddr*)&from,&length);if(n<0)break;
   datagrams++;bytes+=n;peer_udps[i].seen=now;if(from.sin_addr.s_addr!=htonl(0x7f000001))continue;
   struct udp_port *host=NULL;unsigned short source_port=ntohs(from.sin_port);
   for(int h=0;h<MAX_PORT;h++)if(udp_ports[h].pcb&&udp_ports[h].port==source_port){host=&udp_ports[h];break;}
   if(!host)continue; // A closed stream cannot recreate its listener through queued replies.
   struct pbuf *p=pbuf_alloc(PBUF_TRANSPORT,n,PBUF_RAM);if(!p)continue;pbuf_take(p,buffer,n);
   err_t result=udp_sendto(host->pcb,p,&peer_ip,peer_udps[i].port);if(result!=ERR_OK)note("LWIP_UDP_DROP error=%d bytes=%d",result,n);pbuf_free(p);
  }
 }}
 timed(&udp_time,mono_ns()-before,0);
 if((s32_t)(now-stats_at)>=0){
  if(diagnostics_enabled){
  note("LWIP_STATS ip6_rx=%u ip6_drop=%u ip6_chkerr=%u tcp_rx=%u tcp_drop=%u tcp_chkerr=%u tcpPorts=%d udpPorts=%d peers=%d outFrames=%d outBytes=%d outPeakBytes=%d queueFull=%lu timerGapMaxUs=%llu oldestOutUs=%llu",lwip_stats.ip6.recv,lwip_stats.ip6.drop,lwip_stats.ip6.chkerr,lwip_stats.tcp.recv,lwip_stats.tcp.drop,lwip_stats.tcp.chkerr,ntcp,nudp,npeer,qcount,qbytes,qpeak,queue_full,(unsigned long long)(timer_gap_max/1000),(unsigned long long)(qhead?(mono_ns()-qhead->queued_ns)/1000:0));
  for(int i=0;i<MAX_CONN;i++)if(conns[i].pcb){struct connection *c=&conns[i];note("LWIP_TCP_QUEUE connectionId=%lu port=%u rxBytes=%d txBytes=%d sndSpace=%u eagain=%lu rxAgeUs=%llu txAgeUs=%llu noProgressUs=%llu",c->id,c->port,c->rx_size,c->tx_size,tcp_sndbuf(c->pcb),c->would_block,(unsigned long long)(c->rx_since?(mono_ns()-c->rx_since)/1000:0),(unsigned long long)(c->tx_since?(mono_ns()-c->tx_since)/1000:0),(unsigned long long)((mono_ns()-c->progress_ns)/1000));}
  timing_note("scan",&scan_time);timing_note("tcp-pump",&tcp_time);timing_note("udp-pump",&udp_time);timing_note("poll",&poll_time);timing_note("native-output-queue",&output_age);timing_note("tcp-rx-buffer",&tcp_rx_age);timing_note("tcp-tx-buffer",&tcp_tx_age);
  }
  qpeak=qbytes;timer_gap_max=0;stats_at=now+5000;
  for(int i=0;i<MAX_PORT;i++){
   if(tcp_ports[i].pcb&&!tcp_ports[i].refs&&(s32_t)(now-tcp_ports[i].seen)>5000)remove_tcp_port(&tcp_ports[i]);
   if(udp_ports[i].pcb&&!udp_ports[i].refs&&(s32_t)(now-udp_ports[i].seen)>5000)remove_udp_port(&udp_ports[i]);
  }
  for(int i=0;i<MAX_PEER_UDP;i++)if(peer_udps[i].fd>=0&&(s32_t)(now-peer_udps[i].seen)>60000){close(peer_udps[i].fd);peer_udps[i].fd=-1;npeer--;}
 }
 timed(&poll_time,mono_ns()-begin,0);
}
static void close_link(void) {
 if(!active)return;active=0;
 for(int i=0;i<MAX_CONN;i++){if(conns[i].pcb){tcp_arg(conns[i].pcb,NULL);tcp_err(conns[i].pcb,NULL);tcp_recv(conns[i].pcb,NULL);tcp_abort(conns[i].pcb);}forget_connection(&conns[i]);}
 for(int i=0;i<MAX_PORT;i++)remove_tcp_port(&tcp_ports[i]);ntcp=0;
 // This lwIP instance belongs exclusively to this USB attachment. Also remove unaccepted
 // SYN_RCVD and TIME_WAIT state, which otherwise survives a Java service reconnect.
 while(tcp_active_pcbs)tcp_abort(tcp_active_pcbs);
 while(tcp_tw_pcbs)tcp_abort(tcp_tw_pcbs);
 while(tcp_bound_pcbs)tcp_abort(tcp_bound_pcbs);
 for(int i=0;i<MAX_PORT;i++)remove_udp_port(&udp_ports[i]);nudp=0;
 for(int i=0;i<MAX_PEER_UDP;i++){if(peer_udps[i].fd>=0)close(peer_udps[i].fd);peer_udps[i].fd=-1;}npeer=0;
 if(scan_file){fclose(scan_file);scan_file=NULL;}
 netif_set_down(&link_netif);netif_set_link_down(&link_netif);netif_remove(&link_netif);
 while(qhead){struct frame *f=qhead;qhead=f->next;free(f);}qtail=NULL;qcount=0;qbytes=0;
 note("LWIP_CLOSED framesIn=%lu framesOut=%lu",frames_in,frames_out);
}
JNIEXPORT void JNICALL Java_com_shilapi_xcertplay_pioneer_PioneerLwipNative_create(JNIEnv *env,jobject self,jbyteArray mac,jbyteArray addr,jint debug,jstring path) {
 const char *s=(*env)->GetStringUTFChars(env,path,NULL);snprintf(diagnostic_path,sizeof(diagnostic_path),"%s",s);(*env)->ReleaseStringUTFChars(env,path,s);
 (void)self;if(active)close_link();if(!initialized){lwip_init();initialized=1;}
 memset(conns,0,sizeof(conns));for(int i=0;i<MAX_CONN;i++)conns[i].fd=-1;
 memset(&link_netif,0,sizeof(link_netif));netif_add_noaddr(&link_netif,NULL,netif_init_link,ethernet_input);
 (*env)->GetByteArrayRegion(env,mac,0,6,(jbyte*)link_netif.hwaddr);unsigned char bytes[16];(*env)->GetByteArrayRegion(env,addr,0,16,(jbyte*)bytes);
 ip6_addr_t ip;memcpy(ip.addr,bytes,16);ip6_addr_clear_zone(&ip);netif_ip6_addr_set(&link_netif,0,&ip);netif_ip6_addr_set_state(&link_netif,0,IP6_ADDR_PREFERRED);
 netif_set_default(&link_netif);netif_set_up(&link_netif);netif_set_link_up(&link_netif);
 memset(tcp_ports,0,sizeof(tcp_ports));memset(udp_ports,0,sizeof(udp_ports));
 for(int i=0;i<MAX_PEER_UDP;i++)peer_udps[i].fd=-1;
 timer_last=timer_gap_max=0;tcp_cursor=udp_cursor=0;scan_index=0;scan_next=0;qpeak=0;queue_full=0;
 memset(&scan_time,0,sizeof(scan_time));memset(&tcp_time,0,sizeof(tcp_time));memset(&udp_time,0,sizeof(udp_time));memset(&poll_time,0,sizeof(poll_time));memset(&output_age,0,sizeof(output_age));memset(&tcp_rx_age,0,sizeof(tcp_rx_age));memset(&tcp_tx_age,0,sizeof(tcp_tx_age));
 debug_port=debug;peer_known=0;active=1;frames_in=frames_out=0;refresh_at=0;stats_at=0;packet_budget=48;
 note("LWIP_READY IPv6 raw Ethernet; loopback=127.0.0.1 peerAlias=127.0.0.2");poll_link();
}
JNIEXPORT void JNICALL Java_com_shilapi_xcertplay_pioneer_PioneerLwipNative_input(JNIEnv *env,jobject self,jbyteArray data) {
 (void)self;if(!active)return;int n=(*env)->GetArrayLength(env,data);if(n<54||n>65535)return;
 struct pbuf *p=pbuf_alloc(PBUF_RAW,n,PBUF_RAM);if(!p)return;(*env)->GetByteArrayRegion(env,data,0,n,(jbyte*)p->payload);
 unsigned char *b=p->payload;
 if(packet_budget>0){note("LWIP_PACKET_IN next=%u icmp=%u bytes=%d dstHost=%d",b[20],n>54?b[54]:0,n,memcmp(b+38,netif_ip6_addr(&link_netif,0)->addr,16)==0);packet_budget--;}
 if(b[12]==0x86&&b[13]==0xdd&&b[22]==0xfe&&(b[23]&0xc0)==0x80){
  ip_addr_set_zero_ip6(&peer_ip);memcpy(ip_2_ip6(&peer_ip)->addr,b+22,16);peer_known=1;
  ip6_addr_t address;memcpy(address.addr,b+22,16);ip6_addr_assign_zone(&address,IP6_UNICAST,&link_netif);
  int empty=-1,found=-1;
  for(int i=0;i<LWIP_ND6_NUM_NEIGHBORS;i++){
   if(neighbor_cache[i].state==ND6_NO_ENTRY&&empty<0)empty=i;
   if(neighbor_cache[i].netif==&link_netif&&neighbor_cache[i].state!=ND6_NO_ENTRY&&ip6_addr_cmp(&neighbor_cache[i].next_hop_address,&address)){found=i;break;}
  }
  if(found<0)found=empty;
  if(found>=0&&neighbor_cache[found].q==NULL){
   struct nd6_neighbor_cache_entry *e=&neighbor_cache[found];
   if(e->state!=ND6_REACHABLE)note("LWIP_NEIGHBOR_LEARNED from USB Ethernet source");
   ip6_addr_copy(e->next_hop_address,address);e->netif=&link_netif;memcpy(e->lladdr,b+6,6);e->state=ND6_REACHABLE;e->counter.reachable_time=30000;e->isrouter=0;
  }
 }
 frames_in++;if(ethernet_input(p,&link_netif)!=ERR_OK)pbuf_free(p);
}
JNIEXPORT void JNICALL Java_com_shilapi_xcertplay_pioneer_PioneerLwipNative_poll(JNIEnv *env,jobject self){(void)env;(void)self;poll_link();}
JNIEXPORT jbyteArray JNICALL Java_com_shilapi_xcertplay_pioneer_PioneerLwipNative_output(JNIEnv *env,jobject self){(void)self;if(!qhead)return NULL;struct frame *f=qhead;qhead=f->next;if(!qhead)qtail=NULL;qcount--;qbytes-=f->size;timed(&output_age,mono_ns()-f->queued_ns,0);jbyteArray a=(*env)->NewByteArray(env,f->size);if(a)(*env)->SetByteArrayRegion(env,a,0,f->size,(jbyte*)f->data);free(f);return a;}
JNIEXPORT jstring JNICALL Java_com_shilapi_xcertplay_pioneer_PioneerLwipNative_messages(JNIEnv *env,jobject self){(void)self;jstring s=(*env)->NewStringUTF(env,message_buffer);message_size=0;message_buffer[0]=0;return s;}
JNIEXPORT void JNICALL Java_com_shilapi_xcertplay_pioneer_PioneerLwipNative_peerUdp(JNIEnv *env,jobject self,jint port){(void)env;(void)self;if(active)peer_socket(port);}
JNIEXPORT void JNICALL Java_com_shilapi_xcertplay_pioneer_PioneerLwipNative_close(JNIEnv *env,jobject self){(void)env;(void)self;close_link();}

JNIEXPORT jboolean JNICALL Java_com_shilapi_xcertplay_pioneer_PioneerLwipNative_port(JNIEnv *env,jobject self,jint port,jboolean udp,jboolean add){
 (void)env;(void)self;if(!active||port<=0||port>65535)return JNI_FALSE;
 if(udp){
  if(add){struct udp_port *e=add_udp_port(port);if(!e)return JNI_FALSE;e->refs++;}
  else for(int i=0;i<MAX_PORT;i++)if(udp_ports[i].pcb&&udp_ports[i].port==port){if(udp_ports[i].refs>0)udp_ports[i].refs--;if(!udp_ports[i].refs)remove_udp_port(&udp_ports[i]);break;}
 }else{
  if(add){struct tcp_port *e=add_tcp_port(port);if(!e)return JNI_FALSE;e->refs++;}
  else for(int i=0;i<MAX_PORT;i++)if(tcp_ports[i].pcb&&tcp_ports[i].port==port){if(tcp_ports[i].refs>0)tcp_ports[i].refs--;if(!tcp_ports[i].refs)remove_tcp_port(&tcp_ports[i]);break;}
 }
 note("LWIP_PORT_ACK protocol=%s port=%d add=%d tcpPorts=%d udpPorts=%d",udp?"udp":"tcp",port,add,ntcp,nudp);return JNI_TRUE;
}

JNIEXPORT jlong JNICALL Java_com_shilapi_xcertplay_pioneer_PioneerLwipNative_clockNanos(JNIEnv *env,jobject self){(void)env;(void)self;return (jlong)mono_ns();}
JNIEXPORT jlong JNICALL Java_com_shilapi_xcertplay_pioneer_PioneerLwipNative_oldestOutputNanos(JNIEnv *env,jobject self){(void)env;(void)self;return qhead?(jlong)(mono_ns()-qhead->queued_ns):0;}

JNIEXPORT void JNICALL Java_com_shilapi_xcertplay_pioneer_PioneerLwipNative_diagnostics(JNIEnv *env,jobject self,jboolean enabled){
 (void)env;(void)self;diagnostics_enabled=enabled;if(!enabled)packet_budget=0;
}
