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
        cmd = 'PUT /foo 8\n\x54\x65\x4e\x5a\x18\x5a\x48\x70'
        s.send(cmd.encode('utf-8'))
        print('Sent PUT command')
        buf = ''

# close socket
s.close()
