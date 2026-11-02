"""为验收WebM附加Windows本地合成讲解音轨。需要完整FFmpeg（非Playwright裁剪版）。"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import wave

parser=argparse.ArgumentParser()
parser.add_argument('--video',required=True,type=Path)
parser.add_argument('--ffmpeg',type=Path)
parser.add_argument('--browser-mux',action='store_true',help='用本机Chrome编码VP8/Opus，无需完整FFmpeg，耗时约等于视频时长')
args=parser.parse_args()
video=args.video.resolve();folder=video.parent
sentences=[
    '下面演示文件中心的大文件分片、小文件批量上传、刷新后的断点续传，以及实时进度。后半部分逐项讲解生产源码。',
    '大文件按两兆切片，三个分片并行。每片校验哈希，损坏时返回编号，只重传坏片。',
    '小文件共用一次多部分请求。服务器逐文件返回结果，发送结束后仍需等待处理完成。',
    '上传查询已收分片编号。下载把真实分块存入浏览器数据库，刷新后按范围续传并核对版本。',
    '进度按字节计算，包含传输中的部分。只有合并和整文件校验结束，才显示完成。',
    '四路范围下载提高单文件速度。服务器使用虚拟线程、流式读取和并发限额，保护共享资源。'
]
manifest=folder/'narration.json'
manifest.write_text(json.dumps(sentences,ensure_ascii=False),encoding='utf-8')
speech=folder/'synthesize-narration.ps1'
speech.write_text('''param([string]$Manifest,[string]$OutputDirectory)
Add-Type -AssemblyName System.Speech
$lines = [IO.File]::ReadAllText($Manifest,[Text.Encoding]::UTF8) | ConvertFrom-Json
$speaker = New-Object System.Speech.Synthesis.SpeechSynthesizer
try {
    $speaker.SelectVoice('Microsoft Huihui Desktop')
    $speaker.Rate = 2
    for ($i=0; $i -lt $lines.Count; $i++) {
        $speaker.SetOutputToWaveFile((Join-Path $OutputDirectory ("voice-" + $i + ".wav")))
        $speaker.Speak($lines[$i])
        $speaker.SetOutputToNull()
    }
} finally { $speaker.Dispose() }
''',encoding='utf-8-sig')
subprocess.run(['powershell','-NoProfile','-File',str(speech),'-Manifest',str(manifest),'-OutputDirectory',str(folder)],check=True,timeout=90)
if args.browser_mux:
    from playwright.sync_api import sync_playwright
    import base64
    html='''<html><body style="margin:0;background:#111"><video id="video" width="1360" height="1000"></video><script>
    window.mix=async function() {
      const v=document.getElementById('video');
      const loaded=new Promise((ok,no)=>{v.onloadeddata=ok;v.onerror=no});v.src='/video';await loaded;
      const audio=new AudioContext();await audio.resume();const output=audio.createMediaStreamDestination();
      const buffers=await Promise.all(Array.from({length:6},async(_,i)=>audio.decodeAudioData(await(await fetch('/voice-'+i)).arrayBuffer())));
      await v.play();await new Promise(ok=>setTimeout(ok,100));v.pause();v.currentTime=0;
      const stream=new MediaStream([...v.captureStream().getVideoTracks(),...output.stream.getAudioTracks()]);
      if(!stream.getVideoTracks().length)throw new Error('No video track');
      const recorder=new MediaRecorder(stream,{mimeType:'video/webm;codecs=vp8,opus',videoBitsPerSecond:2500000,audioBitsPerSecond:128000});
      const chunks=[];recorder.ondataavailable=e=>{if(e.data.size)chunks.push(e.data)};
      recorder.onstop=()=>{const a=document.createElement('a');a.href=URL.createObjectURL(new Blob(chunks,{type:'video/webm'}));a.download='narrated.webm';a.click()};
      const start=audio.currentTime+.2;const codeStart=Math.max(0,v.duration-43);
      buffers.forEach((buffer,i)=>{const source=audio.createBufferSource();source.buffer=buffer;source.playbackRate.value=Math.max(1,buffer.duration/(i?8:12));source.connect(output);source.start(start+(i?codeStart+(i-1)*8.5:.5))});
      v.onended=()=>recorder.stop();recorder.start(1000);await v.play();
      return v.duration;
    };</script></body></html>'''
    with sync_playwright() as playwright:
        browser=playwright.chromium.launch(channel='chrome',args=['--autoplay-policy=no-user-gesture-required'])
        context=browser.new_context(accept_downloads=True)
        def route(request):
            name=request.request.url.rsplit('/',1)[-1]
            if name=='video':request.fulfill(path=str(video),content_type='video/webm')
            elif name.startswith('voice-'):request.fulfill(path=str(folder/(name+'.wav')),content_type='audio/wav')
            else:request.fulfill(body=html,content_type='text/html')
        context.route('http://media.local/**',route)
        page=context.new_page();page.goto('http://media.local/index')
        target=video.with_name(video.stem+'-含中文合成讲解.webm')
        with page.expect_download(timeout=360000) as download:
            duration=page.evaluate('window.mix()')
            print(f'Browser mux started: {duration:.1f} seconds',flush=True)
        download.value.save_as(target);context.close();browser.close()
    print(target)
    print('Chinese audio is locally synthesized, not the student voice.')
    raise SystemExit(0)
assert args.ffmpeg is not None,'Specify --ffmpeg or --browser-mux'
probe=subprocess.run([str(args.ffmpeg),'-hide_banner','-i',str(video)],capture_output=True,text=True,errors='replace')
match=re.search(r'Duration: (\d+):(\d+):(\d+\.\d+)',probe.stderr)
assert match,'Unable to determine recording duration'
duration=int(match[1])*3600+int(match[2])*60+float(match[3])
code_start=max(0,duration-43)
offsets=[.5]+[code_start+i*8.5 for i in range(5)]
command=[str(args.ffmpeg),'-y','-i',str(video)]
filters=[]
for i,offset in enumerate(offsets):
    audio=folder/f'voice-{i}.wav'
    with wave.open(str(audio),'rb') as sound: length=sound.getnframes()/sound.getframerate()
    command+=['-i',str(audio)]
    max_length=12 if i==0 else 8
    speed=max(1,length/max_length)
    filters.append(f'[{i+1}:a]atempo={speed:.4f},aresample=48000,adelay={round(offset*1000)}:all=1[a{i}]')
filters.append(''.join(f'[a{i}]' for i in range(6))+'amix=inputs=6:duration=longest:normalize=0,apad[audio]')
target=video.with_suffix('.mp4')
command+=['-filter_complex',';'.join(filters),'-map','0:v','-map','[audio]','-c:v','libx264','-preset','veryfast','-crf','24','-pix_fmt','yuv420p','-c:a','aac','-b:a','128k','-shortest','-movflags','+faststart',str(target)]
with (folder/'video-encode.log').open('wb') as log: subprocess.run(command,stdout=log,stderr=log,check=True,timeout=240)
print(target)
print('Local synthetic Chinese narration added; this is not a recording of the student voice.')
