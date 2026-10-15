/** Separate overlapping clips visually without changing their track or render order. */
export function timelineRows(clips:readonly {id:string;trackId:string;at:number;length:number}[]) {
  const slots=new Map<string,number>(),heights=new Map<string,number>(),tracks=new Map<string,typeof clips[number][]>()
  for(const clip of clips){const group=tracks.get(clip.trackId)||[];group.push(clip);tracks.set(clip.trackId,group)}
  for(const [trackId,group] of tracks){const ends:number[]=[]
    for(const clip of group.sort((a,b)=>a.at-b.at)){let row=ends.findIndex(end=>end<=clip.at);if(row<0)row=ends.length;ends[row]=clip.at+clip.length;slots.set(clip.id,row)}
    heights.set(trackId,Math.max(44,ends.length*40+4))
  }
  return {slots,heights}
}
