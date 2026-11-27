from html.parser import HTMLParser
from pathlib import Path
import json
class Parser(HTMLParser):
    def __init__(self):
        super().__init__(); self.nodes=[]
    def handle_starttag(self,tag,attrs):
        attrs=dict(attrs)
        if 'id' in attrs or tag=='button' and ('data-mode' in attrs or 'data-theme' in attrs):
            if 'class' in attrs: attrs['class']=attrs['class'].split()
            self.nodes.append({'tag':tag,'attrs':attrs,'text':''})
root=Path(__file__).resolve().parent
parser=Parser();parser.feed((root/'../app/src/main/assets/index.html').read_text())
(root/'dom.json').write_text(json.dumps(parser.nodes))
