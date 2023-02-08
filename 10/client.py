# tcp client echo
import socket
import sys

# define host and port using argv
HOST = sys.argv[1]
PORT = int(sys.argv[2])

# create socket
s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
# connect to server
s.connect((HOST, PORT))
buf = ''

# recv data from server
while True:
    data = s.recv(1024).decode('utf-8')
    if not data: break
    sys.stdout.write(data)
    buf += data
    if buf.endswith('READY\n'):
        line = sys.stdin.readline()
        while line == '\n':
            line = sys.stdin.readline()
        file_contents = ''
        if line.startswith('PUT'):
            cmd_list = line.split()
            if len(cmd_list) == 3:
                size = int(cmd_list[2])
                file_contents = sys.stdin.read(size)
        s.send((line + file_contents).encode('utf-8'))
        buf = ''

# close socket
s.close()
