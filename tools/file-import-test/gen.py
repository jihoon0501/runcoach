import struct, math, datetime as dt, os, random
random.seed(7)
FIT_EPOCH = 631065600
START_UNIX = int(dt.datetime(2026,10,24,5,12,0,tzinfo=dt.timezone.utc).timestamp())

def profile(kind):
    """(초, 속도 m/s, 심박) 목록. kind: run 또는 walk"""
    pts=[]; t=0
    def seg(sec, v, hr0, hr1):
        nonlocal t
        for i in range(sec):
            pts.append((t, v + random.uniform(-0.12,0.12), int(hr0+(hr1-hr0)*i/max(1,sec-1))))
            t+=1
    if kind=='run':
        seg(180,1.4,100,118)    # 준비운동 걷기
        seg(300,3.0,145,155)    # 본 운동 1
        t+=40                    # 일시정지 40초 (기록 없음)
        seg(120,2.0,150,140)    # 조깅 회복
        seg(420,3.1,150,162)    # 본 운동 2
        seg(180,1.3,130,108)    # 마무리 걷기
    else:
        seg(1800,1.35,95,110)
    return pts

def fit_bytes(kind, sport, cad=85):
    pts=profile(kind)
    dist=0.0; lat=int(37.4*2147483648/180); lon=int(127.0*2147483648/180)
    body=bytearray()
    def defn(local, glob, fields, dev=()):
        h=0x40|local|(0x20 if dev else 0)
        body.extend([h,0,0]); body.extend(struct.pack('<H',glob)); body.append(len(fields))
        for n,s,b in fields: body.extend([n,s,b])
        if dev:
            body.append(len(dev))
            for n,s,i in dev: body.extend([n,s,i])
    # file_id
    defn(0,0,[(0,1,0x00),(253,4,0x86)])
    body.extend([0, 4]); body.extend(struct.pack('<I',START_UNIX-FIT_EPOCH))
    # 개발자 필드가 있는 이벤트 정의 (건너뛰기 시험)
    defn(2,21,[(253,4,0x86),(0,1,0x00)],dev=[(0,4,0)])
    # record 정의
    defn(1,20,[(253,4,0x86),(0,4,0x85),(1,4,0x85),(3,1,0x02),(4,1,0x02),(5,4,0x86),(6,2,0x84)])
    last_ts=None
    for k,(t,v,hr) in enumerate(pts):
        if k>0 and t-pts[k-1][0]>1:
            dist+=0   # 일시정지 동안 이동 없음
        dist+=max(v,0)
        lat+=int(max(v,0)*(2147483648/180)/111320)
        ts=START_UNIX-FIT_EPOCH+t
        body.append(1)
        body.extend(struct.pack('<IiiBBIH',ts,lat,lon,hr,cad,int(dist*100),int(v*1000)))
        last_ts=ts
        if k==60:   # 개발자 필드 메시지 끼워 넣기
            body.append(2); body.extend(struct.pack('<IB',ts,0)); body.extend(b'\x01\x02\x03\x04')
    # 압축 타임스탬프 메시지 3개 (local 3: heart_rate, distance)
    defn(3,20,[(3,1,0x02),(5,4,0x86)])
    for i in range(1,4):
        new_ts=last_ts+i
        dist+=3.0
        body.append(0x80|(3<<5)|(new_ts&0x1F)); body.extend(struct.pack('<BI',160,int(dist*100)))
    end_ts=last_ts+3
    # session
    defn(4,18,[(253,4,0x86),(2,4,0x86),(5,1,0x00),(6,1,0x00),(7,4,0x86),(9,4,0x86),(16,1,0x02),(17,1,0x02),(18,1,0x02)])
    body.append(4)
    hrs=[p[2] for p in pts]
    body.extend(struct.pack('<IIBBIIBBB',end_ts,START_UNIX-FIT_EPOCH,sport,0,int((end_ts-(START_UNIX-FIT_EPOCH))*1000),int(dist*100),int(sum(hrs)/len(hrs)),max(hrs),cad))
    header=bytearray([14,0x20]); header.extend(struct.pack('<H',2132)); header.extend(struct.pack('<I',len(body))); header.extend(b'.FIT'); header.extend(b'\x00\x00')
    return bytes(header)+bytes(body)+b'\x00\x00', dist

def iso(t): return (dt.datetime.fromtimestamp(START_UNIX+t,dt.timezone.utc)).strftime('%Y-%m-%dT%H:%M:%SZ')

def gpx_bytes(kind, with_type=True):
    pts=profile(kind); lat=37.4; lon=127.0
    out=['<?xml version="1.0" encoding="UTF-8"?>','<gpx version="1.1" creator="test" xmlns="http://www.topografix.com/GPX/1/1" xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">',
         '<metadata><time>%s</time></metadata>'%iso(0),'<trk><name>저녁 러닝</name>'+('<type>running</type>' if with_type else ''),'<trkseg>']
    for t,v,hr in pts:
        lat+=max(v,0)/111320
        out.append('<trkpt lat="%.7f" lon="%.7f"><ele>20.0</ele><time>%s</time><extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>%d</gpxtpx:hr><gpxtpx:cad>85</gpxtpx:cad></gpxtpx:TrackPointExtension></extensions></trkpt>'%(lat,lon,iso(t),hr))
    out+=['</trkseg></trk></gpx>']
    return '\n'.join(out).encode('utf-8')

def tcx_bytes(kind, sport):
    pts=profile(kind); dist=0.0; lat=37.4; lon=127.0
    out=['<?xml version="1.0" encoding="UTF-8"?>','<TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2" xmlns:ns3="http://www.garmin.com/xmlschemas/ActivityExtension/v2">',
         '<Activities><Activity Sport="%s"><Id>%s</Id><Lap StartTime="%s"><TotalTimeSeconds>%d</TotalTimeSeconds><DistanceMeters>999999</DistanceMeters><Track>'%(sport,iso(0),iso(0),pts[-1][0])]
    for t,v,hr in pts:
        dist+=max(v,0); lat+=max(v,0)/111320
        out.append('<Trackpoint><Time>%s</Time><Position><LatitudeDegrees>%.7f</LatitudeDegrees><LongitudeDegrees>%.7f</LongitudeDegrees></Position><DistanceMeters>%.1f</DistanceMeters><HeartRateBpm><Value>%d</Value></HeartRateBpm><Extensions><ns3:TPX><ns3:RunCadence>85</ns3:RunCadence></ns3:TPX></Extensions></Trackpoint>'%(iso(t),lat,lon,dist,hr))
    out+=['</Track></Lap></Activity></Activities></TrainingCenterDatabase>']
    return '\n'.join(out).encode('utf-8'), dist

d,fd=fit_bytes('run',1); open('data/run.fit','wb').write(d)
d,wd=fit_bytes('walk',11); open('data/walk.fit','wb').write(d)
open('data/run.gpx','wb').write(gpx_bytes('run'))
open('data/run_notype.gpx','wb').write(gpx_bytes('run',with_type=False))
b,td=tcx_bytes('run','Running'); open('data/run.tcx','wb').write(b)
b,_=tcx_bytes('walk','Other'); open('data/walk.tcx','wb').write(b)
open('data/bad.fit','wb').write(b'\x0e\x20\x54\x08\xff\xff\x00\x00.FIT\x00\x00garbage')
open('data/readme.txt','wb').write(b'hello')
# 기대값
pts=profile('run'); real=sum(max(v,0) for _,v,_ in pts)
print('FIT 총 거리 약 %.0f m, 시간 %d초'%(fd,pts[-1][0]+3))
print('개발자 필드/압축 타임스탬프 포함 FIT 생성 완료')
