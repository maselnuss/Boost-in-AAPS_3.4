import os, numpy as np
import matplotlib as mpl; mpl.use("Agg")
import matplotlib.pyplot as plt
OUT=os.path.dirname(os.path.abspath(__file__))
BLUE,ORANGE,VERM,GREY="#0072B2","#E69F00","#D55E00","#8a8f98"
mpl.rcParams.update({"font.family":"sans-serif","font.sans-serif":["Helvetica Neue","Arial","DejaVu Sans"],
 "font.size":11,"axes.titlesize":13,"axes.titleweight":"bold","axes.labelsize":10.5,
 "axes.spines.top":False,"axes.spines.right":False,"axes.edgecolor":"#4a4f57","figure.dpi":150})
# Predicting the rebound crash at a stuck high, out-of-sample AUC. 0.50 = chance.
rows=[("Glucose trajectory only",0.45,GREY),
      ("+ every efficacy feature",0.50,GREY),
      ("Loop's own deviation signal",0.47,VERM),
      ("Twin inferred carb-appearance",0.47,VERM)]
fig,ax=plt.subplots(figsize=(6.6,3.4))
y=np.arange(len(rows))[::-1]
for yi,(lab,v,c) in zip(y,rows):
    ax.barh(yi,v,color=c,height=0.6,zorder=3)
    ax.text(v+0.005,yi,f"{v:.2f}",va="center",ha="left",fontweight="bold",fontsize=11)
ax.axvline(0.50,color="#111",lw=1.4,ls=(0,(4,3)),zorder=4)
ax.text(0.502,-0.42,"chance (0.50)",fontsize=9,style="italic",color="#111",va="center")
ax.set_yticks(y); ax.set_yticklabels([r[0] for r in rows],fontsize=10)
ax.set_xlim(0.40,0.56); ax.set_xlabel("Out-of-sample AUC for predicting the rebound crash")
ax.set_title("Nothing tells the loop whether its insulin is working")
ax.grid(axis="x",color="#e7e9ec"); ax.grid(axis="y",visible=False); ax.tick_params(length=0); ax.set_axisbelow(True)
plt.tight_layout(); plt.savefig(f"{OUT}/fig_efficacy.png",bbox_inches="tight"); plt.close()
print("written", f"{OUT}/fig_efficacy.png")
