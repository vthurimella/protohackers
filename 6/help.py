from dataclasses import dataclass
inz = '''
Observation(BJ20WNB,28763091,-3722,45,362) on date 332
Observation(BJ20WNB,28748183,-3722,45,601) on date 332
Observation(BJ20WNB,28731778,-3722,45,864) on date 332
Observation(BJ20WNB,28739763,-3722,45,736) on date 332
Observation(BJ20WNB,28785319,-3722,45,8) on date 333
Observation(BJ20WNB,28777644,-3722,45,130) on date 333
'''

@dataclass
class Observation:
  mile: int
  timestamp: int

obs = []
inz = inz.strip()
inz = inz.split('\n')
for line in inz:
  line = line[line.index('(')+1:line.index(')')]
  line = line.split(',')
  obs.append(Observation(int(line[4]), int(line[1])))

for o1 in obs:
  for o2 in obs:
    if o1 != o2:
      dx = o1.mile - o2.mile
      dt = (o1.timestamp - o2.timestamp) / 3600.0
      calcSpeed = abs(dx / dt)
      print(o1, o2, calcSpeed, "dx / dt = {}/{}".format(dx, dt))