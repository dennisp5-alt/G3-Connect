"""Runs a shell command and reports its wall time and the largest memory any of its processes used."""
import resource, subprocess, sys, time
label, count, cmd = sys.argv[1], sys.argv[2], sys.argv[3]
t = time.time()
code = subprocess.call(cmd, shell=True)
took = time.time() - t
peak = resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss / 1024
print(f"TIME {label}: {took:.1f} s for {count} excerpts, peak memory {peak:.0f} MB")
sys.exit(code)
