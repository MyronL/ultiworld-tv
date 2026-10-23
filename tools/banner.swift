import AppKit
let args = CommandLine.arguments
let out = args[1]
let w = 320, h = 180
let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: w, pixelsHigh: h, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
let bg = NSColor(red: 0x0E/255, green: 0x11/255, blue: 0x16/255, alpha: 1)
bg.setFill(); NSRect(x: 0, y: 0, width: w, height: h).fill()
// red band along the bottom, like the site's nav bar
NSColor(red: 0xD2/255, green: 0x23/255, blue: 0x2A/255, alpha: 1).setFill()
NSRect(x: 0, y: 0, width: w, height: 10).fill()
// disc
let red = NSColor(red: 0xD2/255, green: 0x23/255, blue: 0x2A/255, alpha: 1)
let teal = NSColor(red: 0x00/255, green: 0x83/255, blue: 0x92/255, alpha: 1)
red.setFill(); NSBezierPath(ovalIn: NSRect(x: 24, y: 58, width: 72, height: 72)).fill()
teal.setFill(); NSBezierPath(ovalIn: NSRect(x: 36, y: 70, width: 48, height: 48)).fill()
func draw(_ s: String, _ size: CGFloat, _ weight: NSFont.Weight, _ color: NSColor, _ x: CGFloat, _ y: CGFloat, kern: CGFloat = 0) {
  let attrs: [NSAttributedString.Key: Any] = [.font: NSFont.systemFont(ofSize: size, weight: weight), .foregroundColor: color, .kern: kern]
  NSAttributedString(string: s, attributes: attrs).draw(at: NSPoint(x: x, y: y))
}
draw("ULTIWORLD", 34, .heavy, .white, 108, 86, kern: 0.5)
draw("TV", 26, .bold, teal, 110, 54, kern: 2)
NSGraphicsContext.restoreGraphicsState()
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: out))
