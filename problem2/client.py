import socket
import sys

HOST, PORT = "localhost", 9091

m1 = b'\x49\x00\x00\x30\x39\x00\x00\x00\x65'
m2 = b'\x49\x00\x00\x30\x3a\x00\x00\x00\x66'
m3 = b'\x49\x00\x00\x30\x3b\x00\x00\x00\x64'
m4 = b'\x49\x00\x00\xa0\x00\x00\x00\x00\x05'
q1 = b'\x51\x00\x00\x30\x00\x00\x00\x40\x00'


# Create a socket (SOCK_STREAM means a TCP socket)
with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
    # Connect to server and send data
    sock.connect((HOST, PORT))
    sock.send(bytes(m1 + m2 + m3 + m4))
    sock.send(bytes(q1))
    # sock.sendall(bytes(data + "\n", "utf-8"))

    # Receive data from the server and shut down
    received = int.from_bytes(sock.recv(4), 'big')

print("Sent:     {}".format('{"method":"isPrime"}'))
print("Received: {}".format(received))
