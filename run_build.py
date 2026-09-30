import os, subprocess

ROOT = r"C:\Users\Administrator\Documents\trae_projects\AI\learn_demos\252_模拟世界游戏_会呼吸的世界\java\breathing-world"
os.chdir(ROOT)
py = r"C:\Users\Administrator\.workbuddy\binaries\python\versions\3.13.12\python.exe"
out = os.path.join(ROOT, "build_out.txt")
env = dict(os.environ)
env["BW_KEEP_OUT"] = "1"   # 跳过 out/ 批量删除（沙箱对 >50 文件删除会拦截）
with open(out, "w", encoding="utf-8") as f:
    p = subprocess.run([py, "build_runner.py"], stdout=f, stderr=subprocess.STDOUT, env=env)
print("BUILD_EXIT", p.returncode)
