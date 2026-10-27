#!/usr/bin/env python3
"""Build the two ARM instrument helpers from source; no vehicle access."""
from pathlib import Path
import argparse
import hashlib
import os
import re
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[1]

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--ndk',type=Path,help='Android NDK 28.2.13676358 directory')
    ns=ap.parse_args()
    sdk=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
    ndk=ns.ndk or (Path(sdk)/'ndk/28.2.13676358' if sdk else None)
    if ndk is None: ap.error('Provide --ndk or ANDROID_HOME')
    hosts=list((ndk/'toolchains/llvm/prebuilt').glob('*'))
    if len(hosts)!=1: ap.error('Expected exactly one installed NDK host toolchain')
    tool=hosts[0]; cc=tool/'bin/armv7a-linux-androideabi28-clang'
    if not cc.is_file(): ap.error('This script needs the macOS/Linux NDK toolchain')
    builtins=list((tool/'lib/clang').glob('*/lib/linux/libclang_rt.builtins-arm-android.a'))
    if len(builtins)!=1: ap.error('ARM compiler builtins missing or ambiguous')
    output=ROOT/'build/instrument-native'; output.mkdir(parents=True,exist_ok=True)
    base=[str(cc),'-O3','-DNDEBUG','-fno-strict-aliasing','-fno-builtin','-fno-stack-protector','-ffreestanding','-ffunction-sections','-fdata-sections','-I'+str(ROOT/'native')]
    units=['ultra_app_receiver.c','ultra_tile_decode.c','lz4/lz4.c','tinf/tinflate.c','tinf/tinfzlib.c','tinf/adler32.c','ultra_neon.c']
    objects=[]
    for unit in units:
        obj=output/(Path(unit).stem+'.o')
        flags=['-mfloat-abi=softfp','-mfpu=neon'] if unit=='ultra_neon.c' else ['-mfloat-abi=soft','-mfpu=none','-fno-vectorize','-fno-slp-vectorize']
        if unit in ['ultra_tile_decode.c','lz4/lz4.c']:
            flags+=['-DLZ4_FREESTANDING=1','-DLZ4_memcpy=memcpy','-DLZ4_memmove=memmove','-DLZ4_memset=memset','-include',str(ROOT/'native/ultra_freestanding.h')]
        subprocess.run(base+flags+['-c',str(ROOT/'native'/unit),'-o',str(obj)],check=True);objects.append(str(obj))
    assets=ROOT/'common/src/main/assets/ultra';assets.mkdir(parents=True,exist_ok=True)
    receiver=assets/'ultra_app_receiver'
    link=['-nostdlib','-static','-fuse-ld=lld','-Wl,-e,_start','-Wl,--build-id=none','-Wl,--gc-sections']
    subprocess.run([str(cc),*link,*objects,str(builtins[0]),'-o',str(receiver)],check=True)
    monitor=assets/'ultra_trip_monitor'
    subprocess.run(base+['-mfloat-abi=soft','-mfpu=none','-fno-vectorize','-fno-slp-vectorize',*link,str(ROOT/'native/ultra_trip_monitor.c'),str(builtins[0]),'-o',str(monitor)],check=True)
    for binary,rel,key in [(receiver,'UltraProjectionRunner.java','ASSET_HASH'),(monitor,'UltraTripReader.java','HASHES')]:
        sha=hashlib.sha256(binary.read_bytes()).hexdigest()
        source=ROOT/'common/src/main/java/com/heavendoor/ultralab'/rel
        text=source.read_text()
        pattern=rf'{key}="[0-9a-f]{{64}}"' if key=='ASSET_HASH' else r'HASHES=\{"[0-9a-f]{64}"'
        if len(re.findall(pattern,text))!=1: raise RuntimeError(f'Expected one {key} declaration')
        replacement=f'{key}="{sha}"' if key=='ASSET_HASH' else 'HASHES={"'+sha+'"'
        source.write_text(re.sub(pattern,lambda _:replacement,text))
        print(f'{binary.name}: {sha} ({binary.stat().st_size} bytes)')
    return 0

if __name__=='__main__':sys.exit(main())
