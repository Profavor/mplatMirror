import http.server
import ssl
import os

os.chdir('/home/profavor/mMirror/dist')
server_address = ('0.0.0.0', 8088)
httpd = http.server.ThreadingHTTPServer(server_address, http.server.SimpleHTTPRequestHandler)

context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.load_cert_chain(
    certfile='/home/profavor/.letsencrypt/live/mdm.mplat.store/fullchain.pem',
    keyfile='/home/profavor/.letsencrypt/live/mdm.mplat.store/privkey.pem'
)
httpd.socket = context.wrap_socket(httpd.socket, server_side=True)

print("Serving official HTTPS on https://0.0.0.0:8088 (mdm.mplat.store)")
httpd.serve_forever()
