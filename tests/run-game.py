from pathlib import Path
import subprocess,tempfile,os
root=Path(__file__).resolve().parent.parent
subprocess.run(['python3',str(root/'tests/generate-dom.py')],check=True)
with tempfile.TemporaryDirectory() as out:
    import shutil
    compiler=['javac'] if shutil.which('javac') else ['java','com.sun.tools.javac.Main']
    sources=[str(root/f'app/src/main/java/com/worta/tanks/{n}.java') for n in ('Json','RoomEngine','LocalHttpServer')]+[str(root/'tests/DevelopmentServer.java')]
    subprocess.run(compiler+['-d',out]+sources,check=True)
    server=subprocess.Popen(['java','-cp',out,'com.worta.tanks.DevelopmentServer'],stdout=subprocess.PIPE,text=True)
    try:
        port=server.stdout.readline().strip();env=os.environ.copy();env['TANK_TEST_PORT']=port
        result=subprocess.run(['node',str(root/'tests/game.cjs')],env=env)
        raise SystemExit(result.returncode)
    finally:
        server.terminate();server.wait(timeout=5)
